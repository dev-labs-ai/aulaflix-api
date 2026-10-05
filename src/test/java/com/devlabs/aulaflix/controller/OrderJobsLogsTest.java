package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminCourses.newSlug;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.AdminCourses;
import com.devlabs.aulaflix.Asaas;
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.Cpfs;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StoredOutboxEmails;
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.StudentOrders;
import com.devlabs.aulaflix.service.AccountService;
import com.devlabs.aulaflix.service.OrderExpiry;
import com.devlabs.aulaflix.service.OrderReconciliation;

/**
 * The expiry job and reconciliation log Order codes, never the Student's email, a CPF nor Asaas's key. An expiry is an
 * INFO line; an Asaas outage, which the next run retries, a WARN; a refusal, which a retry will not fix, an ERROR with
 * what Asaas said.
 */
@ExtendWith(OutputCaptureExtension.class)
class OrderJobsLogsTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";
    private static final int PIX_PRICE_CENTS = 44730;

    @Autowired
    private AccountService accounts;

    @Autowired
    private StoredVideos storedVideos;

    @Autowired
    private Asaas asaas;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OrderExpiry expiry;

    @Autowired
    private OrderReconciliation reconciliation;

    @Value("${aulaflix.asaas.paid-recheck-interval}")
    private Duration paidRecheckInterval;

    private long course;

    private String email;

    private String cpf;

    private StudentOrders orders;

    @BeforeEach
    void putACourseOnSaleAndSignInAStudent() {
        String admin = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(admin, "Ana", PASSWORD);
        course = new AdminCourses(mvc, new AdminApi(mvc).sessionToken(admin, PASSWORD), storedVideos)
                .onSale(newSlug());
        BffApi bff = new BffApi(mvc);
        email = StudentApi.newEmail();
        cpf = Cpfs.newCpf();
        orders = new StudentOrders(bff, new StudentApi(bff).signedUp(email, PASSWORD));
    }

    /**
     * A job pays whatever Order of the whole suite is due, and a Duplicate payment it finds alerts every Admin the
     * suite has made, hundreds of them: what it queued is discarded, so that no later test's drain waits behind it.
     */
    @AfterEach
    void discardWhatTheJobsQueued() {
        new StoredOutboxEmails(jdbc).discardPending();
    }

    @Test
    void logsTheOrdersCodeButNoEmailCpfOrKey(CapturedOutput output) {
        asaas.answerNextChargeFor(Asaas.customerOf(cpf), Asaas.tooLate());
        asaas.answerNextChargeSearchWith(serverError());
        orders.placePix(course, cpf);
        String paid = orders.placedPix(course, null);
        asaas.chargeIs(Asaas.chargeOf(paid), "CONFIRMED", PIX_PRICE_CENTS, paid, false);
        clock.set(clock.instant().plus(Duration.ofMinutes(5)));
        reconciliation.reconcile();
        String expired = orders.placedPix(otherCourse(), null);
        asaas.chargeIs(Asaas.chargeOf(expired), "PENDING", PIX_PRICE_CENTS, expired, false);
        clock.set(clock.instant().plus(Duration.ofMinutes(30)));

        expiry.expireDue();

        assertThat(output.getAll()).contains("INFO", "Order " + paid + " was paid", "Order " + expired + " expired")
                .doesNotContain(email, cpf, Asaas.API_KEY);
    }

    @Test
    void logsAnAsaasOutageAtWarnAndARefusalAtErrorWithWhatAsaasSaid(CapturedOutput output) {
        String unreachable = orders.placedPix(course, cpf);
        asaas.answerNextChargeReadWith(Asaas.chargeOf(unreachable), serverError());
        clock.set(clock.instant().plus(Duration.ofMinutes(30)));
        expiry.expireDue();
        String refused = orders.placedPix(otherCourse(), null);
        asaas.answerChargeReadsWith(Asaas.chargeOf(refused), Asaas.error(404, "invalid_payment"));
        clock.set(clock.instant().plus(Duration.ofMinutes(30)));

        expiry.expireDue();

        assertThat(output.getAll())
                .containsPattern("WARN .*Left Order " + unreachable
                        + " and the rest to expire on the next run: Asaas failed reading a charge: HTTP 500")
                .containsPattern("ERROR .*Expiring Order " + refused
                        + " without its charge: Asaas refused reading a charge: HTTP 404 \\[invalid_payment]");
    }

    @Test
    void logsAPaidOrdersReReadAsaasRefusesAtErrorWithWhatAsaasSaid(CapturedOutput output) {
        String paid = orders.placedPix(course, cpf);
        asaas.chargeIs(Asaas.chargeOf(paid), "CONFIRMED", PIX_PRICE_CENTS, paid, false);
        clock.set(clock.instant().plus(Duration.ofMinutes(5)));
        reconciliation.reconcile();
        asaas.answerNextChargeReadWith(Asaas.chargeOf(paid), Asaas.error(404, "invalid_payment"));
        clock.set(clock.instant().plus(paidRecheckInterval));

        reconciliation.reconcile();

        assertThat(output.getAll()).containsPattern("ERROR .*Could not re-read the charge of paid Order " + paid
                + ": Asaas refused reading a charge: HTTP 404 \\[invalid_payment]");
    }

    private long otherCourse() {
        String admin = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(admin, "Ana", PASSWORD);
        return new AdminCourses(mvc, new AdminApi(mvc).sessionToken(admin, PASSWORD), storedVideos)
                .onSale(newSlug());
    }
}
