package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminCourses.newSlug;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.AdminCourses;
import com.devlabs.aulaflix.Asaas;
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.Cpfs;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StoredAccounts;
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.StudentOrders;
import com.devlabs.aulaflix.service.AccountService;

/**
 * Cancelling an Order, by hand or by switching methods, logs the Student's id and the Order's code, never the Student's
 * email, CPF nor Asaas's key. An Asaas refusal that leaves the Order awaiting payment is logged at ERROR, with what
 * Asaas said; an outage at WARN.
 */
@ExtendWith(OutputCaptureExtension.class)
class OrderCancellationLogsTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private AccountService accounts;

    @Autowired
    private StoredVideos storedVideos;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Asaas asaas;

    private long course;

    @BeforeEach
    void putACourseOnSale() {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(email, "Ana", PASSWORD);
        course = new AdminCourses(mvc, new AdminApi(mvc).sessionToken(email, PASSWORD), storedVideos)
                .onSale(newSlug());
    }

    @Test
    void logsTheCancellationsButNoEmailCpfOrKey(CapturedOutput output) {
        String email = StudentApi.newEmail();
        String cpf = Cpfs.newCpf();
        StudentOrders orders = signedUp(email);
        String cancelled = orders.placedCard(course);
        orders.cancel(cancelled);
        String switched = orders.placedPix(course, cpf);
        orders.placedCard(course);

        long student = new StoredAccounts(jdbc).find(email).orElseThrow().id();
        assertThat(output.getAll())
                .contains("Student %d cancelled Order %s".formatted(student, cancelled),
                        "Student %d cancelled Order %s".formatted(student, switched))
                .doesNotContain(email, cpf, Asaas.API_KEY);
    }

    @Test
    void logsAnAsaasRefusalAtErrorWithWhatAsaasSaid(CapturedOutput output) {
        StudentOrders orders = signedUp(StudentApi.newEmail());
        String code = orders.placedCard(course);
        asaas.answerNextCancellationOf(Asaas.checkoutOf(code), Asaas.error(400, "invalid_status"));
        asaas.cardSale(code, "AWAITING_RISK_ANALYSIS", 1, 49700, code);

        orders.cancel(code);

        assertThat(output.getAll()).containsPattern(
                "ERROR .*Refused POST /v1/account/orders/%s/cancellation: payment-provider-error; Order %s left "
                        .formatted(code, code)
                        + "awaiting payment; Asaas refused cancelling a Checkout: HTTP 400 \\[invalid_status]");
    }

    @Test
    void logsAnAsaasOutageAtWarnWithItsCause(CapturedOutput output) {
        StudentOrders orders = signedUp(StudentApi.newEmail());
        String code = orders.placedCard(course);
        asaas.answerNextCancellationOf(Asaas.checkoutOf(code), serverError());

        orders.cancel(code);

        assertThat(output.getAll()).containsPattern(
                "WARN .*Refused POST /v1/account/orders/%s/cancellation: payment-unavailable; Order %s left "
                        .formatted(code, code)
                        + "awaiting payment; Asaas failed cancelling a Checkout: HTTP 500");
    }

    private StudentOrders signedUp(String email) {
        BffApi bff = new BffApi(mvc);
        return new StudentOrders(bff, new StudentApi(bff).signedUp(email, PASSWORD));
    }
}
