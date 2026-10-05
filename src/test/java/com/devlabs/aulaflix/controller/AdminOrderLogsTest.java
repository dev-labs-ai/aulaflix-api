package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminCourses.newSlug;
import static com.devlabs.aulaflix.AsaasWebhooks.newEventId;
import static com.devlabs.aulaflix.AsaasWebhooks.paymentEvent;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.AdminCourses;
import com.devlabs.aulaflix.AdminOrders;
import com.devlabs.aulaflix.Asaas;
import com.devlabs.aulaflix.AsaasWebhooks;
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.Cpfs;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StoredAccounts;
import com.devlabs.aulaflix.StoredOutboxEmails;
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.StudentOrders;
import com.devlabs.aulaflix.service.AccountService;
import com.devlabs.aulaflix.service.OrderReconciliation;
import com.devlabs.aulaflix.service.WebhookWorker;

/**
 * A refund is an Admin mutation: it logs one INFO line naming the Admin by id, so the Order's own audit has a trail
 * beside it. Logs must not become a leak: no line carries the Student's or the Admin's email or name, a CPF, or a
 * token, nor the descriptions Asaas gives with a refusal, which may repeat what was sent.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminOrderLogsTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";
    private static final int PIX_PRICE_CENTS = 44730;

    @Autowired
    private AccountService accounts;

    @Autowired
    private StoredVideos storedVideos;

    @Autowired
    private Asaas asaas;

    @Autowired
    private WebhookWorker worker;

    @Autowired
    private OrderReconciliation reconciliation;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminEmail;

    private String adminName;

    private String adminToken;

    private AdminCourses courses;

    private AdminOrders adminOrders;

    @BeforeEach
    void signInAnAdmin() {
        adminEmail = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        adminName = "Ana " + UUID.randomUUID();
        accounts.createAdmin(adminEmail, adminName, PASSWORD);
        adminToken = new AdminApi(mvc).sessionToken(adminEmail, PASSWORD);
        courses = new AdminCourses(mvc, adminToken, storedVideos);
        adminOrders = new AdminOrders(mvc, adminToken);
    }

    /** Reconciliation follows every Order of the whole suite: what it queued is discarded. */
    @AfterEach
    void discardWhatTheJobsQueued() {
        new StoredOutboxEmails(jdbc).discardPending();
    }

    @Test
    void logsOneInfoLineWithTheAdminsIdForARefundAndNoneForARepeatedOne(CapturedOutput output) {
        String code = paidOrder(StudentApi.newEmail(), "Bia", Cpfs.newCpf());
        long adminId = new StoredAccounts(jdbc).find(adminEmail).orElseThrow().id();
        String byTheAdmin = "Admin " + adminId + " ";

        int before = output.getAll().length();
        assertThat(adminOrders.refund(code)).hasStatusOk();
        List<String> refundLines = infoLinesSince(output, before);
        before = output.getAll().length();
        assertThat(adminOrders.refund(code)).hasStatusOk();
        List<String> repeatLines = infoLinesSince(output, before);

        assertThat(refundLines).filteredOn(line -> line.contains(byTheAdmin)).singleElement().asString()
                .contains("refunded Order " + code);
        assertThat(repeatLines).noneMatch(line -> line.contains(byTheAdmin));
    }

    @Test
    void logsNoPersonalDataTokensNorAsaasDescriptions(CapturedOutput output) {
        String studentEmail = StudentApi.newEmail();
        String studentName = "Bia " + UUID.randomUUID();
        String cpf = Cpfs.newCpf();
        String refunded = paidOrder(studentEmail, studentName, cpf);
        String refused = paidOrder(StudentApi.newEmail(), "Caio", Cpfs.newCpf());
        String description = "Saldo insuficiente " + UUID.randomUUID();
        asaas.answerNextRefundOf(Asaas.chargeOf(refused), Asaas.error(400, "invalid_action", description));

        adminOrders.refund(refunded);
        adminOrders.refund(refused);
        adminOrders.list("email=" + studentEmail);
        adminOrders.get(refunded);
        asaas.chargeIsRefunded(Asaas.chargeOf(refunded), "DONE", PIX_PRICE_CENTS, refunded);
        reconciliation.reconcile();

        assertThat(output.getAll()).isNotBlank()
                .contains("Order " + refunded + " was refunded")
                .doesNotContainIgnoringCase(studentEmail)
                .doesNotContainIgnoringCase(adminEmail)
                .doesNotContain(studentName, adminName, cpf, description, adminToken);
    }

    private static List<String> infoLinesSince(CapturedOutput output, int before) {
        return output.getAll().substring(before).lines().filter(line -> line.contains(" INFO ")).toList();
    }

    /** A Pix Order placed by a new Student and paid: its webhook came, and the worker ran. */
    private String paidOrder(String email, String name, String cpf) {
        BffApi bff = new BffApi(mvc);
        StudentOrders orders = new StudentOrders(bff, AdminApi.tokenOf(new StudentApi(bff).signUp(name, email,
                PASSWORD)));
        String code = orders.placedPix(courses.onSale(newSlug()), cpf);
        String charge = Asaas.chargeOf(code);
        asaas.chargeIs(charge, "CONFIRMED", PIX_PRICE_CENTS, code, false);
        assertThat(new AsaasWebhooks(mvc).deliver(paymentEvent(newEventId(), "PAYMENT_CONFIRMED", charge,
                "CONFIRMED", PIX_PRICE_CENTS, code))).hasStatusOk();
        worker.processPending();
        return code;
    }
}
