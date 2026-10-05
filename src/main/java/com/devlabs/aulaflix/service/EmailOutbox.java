package com.devlabs.aulaflix.service;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.devlabs.aulaflix.domain.entity.OutboxEmailEntity;
import com.devlabs.aulaflix.repository.OutboxEmailRepository;
import com.devlabs.aulaflix.service.OutboxQueue.QueuedEmail;
import com.devlabs.aulaflix.service.OutboxQueue.Retry;

/**
 * The Email outbox: every email the API sends is queued in the transaction of what it tells of, and sent later by the
 * drainer, so that slow SMTP never fails a request, and a message is never lost. A request only ever queues.
 */
@Service
public class EmailOutbox {

    private static final Logger log = LoggerFactory.getLogger(EmailOutbox.class);

    private final OutboxEmailRepository repository;
    private final OutboxQueue queue;
    private final JavaMailSender mailSender;
    private final Clock clock;
    private final InternetAddress from;
    private final SendRate rate;

    public EmailOutbox(OutboxEmailRepository repository, OutboxQueue queue, JavaMailSender mailSender, Clock clock,
                       OutboxSettings settings) {
        this.repository = repository;
        this.queue = queue;
        this.mailSender = mailSender;
        this.clock = clock;
        this.from = settings.from();
        this.rate = new SendRate(settings.emailsPerSpan(), settings.span());
    }

    /** Queues the email within the caller's transaction: it goes out only if what it tells of commits. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(OutboundEmail email) {
        OutboxEmailEntity queued = repository.save(new OutboxEmailEntity(email.template(), email.recipient(),
                email.subject(), email.body(), email.headers(), clock.instant()));
        log.info("Queued outbox email {} ({})", queued.getId(), email.template());
    }

    /**
     * Sends the emails that are due, the one due longest first, as many as the send rate allows now, and answers how
     * many went out. It stops at the first failure: a server that is down or silent would fail the rest the same way,
     * and each would wait out the SMTP timeouts. Only one drain runs at a time: the scheduled job, whose fixed delay
     * never overlaps two runs, or a test, once, synchronously, with the job off.
     */
    public int drain() {
        int allowed = rate.available(clock.instant());
        if (allowed == 0) {
            return 0;
        }
        List<QueuedEmail> due = queue.due(clock.instant(), allowed);
        int sent = 0;
        for (QueuedEmail email : due) {
            rate.record(clock.instant());
            if (!send(email)) {
                break;
            }
            sent++;
        }
        return sent;
    }

    /** Only the failure's class is logged: an SMTP server's message may quote the recipient's address. */
    private boolean send(QueuedEmail email) {
        try {
            mailSender.send(message -> prepare(message, email));
        } catch (MailException failure) {
            Retry retry = queue.recordFailure(email.id(), clock.instant());
            log.warn("Outbox email {} failed on attempt {} ({}); next attempt at {}", email.id(), retry.attempts(),
                    failure.getClass().getSimpleName(), retry.nextAttemptAt());
            return false;
        }
        queue.recordSent(email.id(), clock.instant());
        log.info("Sent outbox email {}", email.id());
        return true;
    }

    private void prepare(MimeMessage message, QueuedEmail email) throws MessagingException {
        MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
        helper.setFrom(from);
        helper.setTo(email.recipient());
        helper.setSubject(email.subject());
        helper.setText(email.body());
        for (Map.Entry<String, String> header : email.headers().entrySet()) {
            message.setHeader(header.getKey(), header.getValue());
        }
    }
}
