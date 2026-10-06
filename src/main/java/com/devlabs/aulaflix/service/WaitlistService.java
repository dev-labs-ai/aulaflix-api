package com.devlabs.aulaflix.service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.devlabs.aulaflix.domain.CourseStatus;
import com.devlabs.aulaflix.domain.entity.AccountEntity;
import com.devlabs.aulaflix.domain.entity.CourseEntity;
import com.devlabs.aulaflix.dto.CoursePricing;
import com.devlabs.aulaflix.exception.InvalidUnsubscribeLinkException;
import com.devlabs.aulaflix.exception.NotOnWaitlistException;
import com.devlabs.aulaflix.exception.SessionRequiredException;
import com.devlabs.aulaflix.exception.WaitlistClosedException;
import com.devlabs.aulaflix.repository.AccountRepository;
import com.devlabs.aulaflix.repository.CourseRepository;
import com.devlabs.aulaflix.repository.WaitlistEntryRepository;

/**
 * The Waitlists of Coming soon Courses, whose entries hold a normalized email and nothing else: a Visitor's, or a
 * Student's Account email, so a Student is on a Waitlist whenever an entry holds their email, however it got there.
 * Joining is single opt-in and idempotent, sends no email, and answers alike whoever is listed already. The launch
 * emails every entry once and empties the Waitlist; each email's unsubscribe token takes its address off every one.
 */
@Service
public class WaitlistService {

    private static final Logger log = LoggerFactory.getLogger(WaitlistService.class);

    private final WaitlistEntryRepository entries;
    private final CourseRepository courses;
    private final AccountRepository accounts;
    private final EmailOutbox outbox;
    private final EmailTemplates templates;
    private final UnsubscribeTokens unsubscribeTokens;
    private final Clock clock;

    public WaitlistService(WaitlistEntryRepository entries, CourseRepository courses, AccountRepository accounts,
                           EmailOutbox outbox, EmailTemplates templates, UnsubscribeTokens unsubscribeTokens,
                           Clock clock) {
        this.entries = entries;
        this.courses = courses;
        this.accounts = accounts;
        this.outbox = outbox;
        this.templates = templates;
        this.unsubscribeTokens = unsubscribeTokens;
        this.clock = clock;
    }

    /**
     * A Visitor joins with an email, which is checked once normalized, before the Course. The Course's id is taken as
     * the body carries it, so that an id of any shape answers like an unknown one.
     */
    @Transactional
    public void joinAsVisitor(String courseId, String email) {
        String normalizedEmail = AccountInputRules.normalizeEmail(email);
        AccountInputRules.requireValid(AccountInputRules.emailViolations(normalizedEmail));
        add(PathIds.parse(courseId).orElseThrow(WaitlistClosedException::new), normalizedEmail);
    }

    /** The Student joins with the Account's email. */
    @Transactional
    public void joinAsStudent(long studentId, String courseId) {
        add(PathIds.parse(courseId).orElseThrow(WaitlistClosedException::new), emailOf(studentId));
    }

    @Transactional(readOnly = true)
    public void requireOn(long studentId, String courseId) {
        Optional<Long> course = PathIds.parse(courseId);
        if (course.isEmpty() || !entries.existsByCourseIdAndEmail(course.get(), emailOf(studentId))) {
            throw new NotOnWaitlistException();
        }
    }

    /** Leaving a Waitlist the Student is not on, or a Course that does not exist, changes nothing. */
    @Transactional
    public void leave(long studentId, String courseId) {
        PathIds.parse(courseId).ifPresent(course -> entries.deleteByCourseIdAndEmail(course, emailOf(studentId)));
    }

    /**
     * The launch's fan-out, in the transaction that moves the Course from Coming soon to On sale, under its row lock:
     * one launch email queued per entry, but none to a Student with an active Enrollment in the Course, then every
     * entry deleted. It answers how many emails it queued. If the move rolls back, so do the emails and the deletions,
     * and a retried move finds the Course On sale already and sends nothing.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int notifyLaunch(CourseEntity course) {
        List<String> emails = entries.findEmailsToNotifyOfLaunch(course.getId());
        CoursePricing pricing = Pricing.of(course);
        for (String email : emails) {
            outbox.enqueue(templates.waitlistLaunch(email, course.getTitle(), course.getSummary(), course.getSlug(),
                    pricing, unsubscribeTokens.of(email)));
        }
        entries.deleteByCourseId(course.getId());
        log.info("Launch of Course {} notified {} on its Waitlist", course.getId(), emails.size());
        return emails.size();
    }

    /**
     * Takes the token's email off every Waitlist, and answers alike whether it was on any. A token the key did not
     * make, or one changed in any way, is refused. Joining again later is fresh consent.
     */
    @Transactional
    public void unsubscribe(String token) {
        String email = unsubscribeTokens.emailIn(token).orElseThrow(InvalidUnsubscribeLinkException::new);
        int removed = entries.deleteByEmail(email);
        log.info("An unsubscribe link removed {} Waitlist entries", removed);
    }

    /**
     * Under a shared lock on the Course's row, so that the launch, which takes the row lock, either waits for the
     * entry or finds the Course On sale already, and never leaves an entry behind.
     */
    private void add(long courseId, String normalizedEmail) {
        courses.findSharedLockedById(courseId)
                .filter(course -> course.getStatus() == CourseStatus.COMING_SOON)
                .orElseThrow(WaitlistClosedException::new);
        entries.insertIfAbsent(courseId, normalizedEmail, clock.instant().truncatedTo(ChronoUnit.MICROS));
    }

    /** Account emails are stored normalized. */
    private String emailOf(long studentId) {
        return accounts.findById(studentId).map(AccountEntity::getEmail).orElseThrow(SessionRequiredException::new);
    }
}
