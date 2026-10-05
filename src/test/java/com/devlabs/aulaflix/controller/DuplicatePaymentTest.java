package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminCourses.newSlug;
import static com.devlabs.aulaflix.AsaasWebhooks.newEventId;
import static com.devlabs.aulaflix.AsaasWebhooks.paymentEvent;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.AdminCourses;
import com.devlabs.aulaflix.AdminEnrollments;
import com.devlabs.aulaflix.Asaas;
import com.devlabs.aulaflix.AsaasWebhooks;
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.Cpfs;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.Mailpit;
import com.devlabs.aulaflix.StoredAccounts;
import com.devlabs.aulaflix.StoredOrders;
import com.devlabs.aulaflix.StoredOutboxEmails;
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.StudentOrders;
import com.devlabs.aulaflix.service.AccountService;
import com.devlabs.aulaflix.service.EmailOutbox;
import com.devlabs.aulaflix.service.WebhookWorker;

/**
 * A Duplicate payment: a race leaves a second Order of the Course paid while the Student already has it. That Order
 * stays paid, but grants nothing; the Student keeps the Enrollment they had, and every Admin is emailed, once, to
 * refund it by hand. Placing that second Order is refused through the API, so the test inserts it, as the race left it.
 * Every Admin the whole suite has made gets the alert, hundreds of them: the stored queue shows that each got one, and
 * Mailpit what the test's own Admins received.
 */
class DuplicatePaymentTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";
    private static final int PIX_PRICE_CENTS = 44730;
    private static final String COURSE_TITLE = "Backend com Node.js";
    private static final String ALERT_TEMPLATE = "DUPLICATE_PAYMENT_ALERT";

    @Autowired
    private AccountService accounts;

    @Autowired
    private StoredVideos storedVideos;

    @Autowired
    private Asaas asaas;

    @Autowired
    private WebhookWorker worker;

    @Autowired
    private EmailOutbox outbox;

    @Autowired
    private Mailpit mailpit;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminEmail;

    private AdminCourses courses;

    private AdminEnrollments enrollments;

    private AsaasWebhooks webhooks;

    private StoredOrders storedOrders;

    private StoredOutboxEmails storedEmails;

    private String studentEmail;

    private StudentOrders orders;

    @BeforeEach
    void signInAnAdminAndAStudent() {
        adminEmail = newAdminEmail();
        accounts.createAdmin(adminEmail, "Ana", PASSWORD);
        String adminToken = new AdminApi(mvc).sessionToken(adminEmail, PASSWORD);
        courses = new AdminCourses(mvc, adminToken, storedVideos);
        enrollments = new AdminEnrollments(mvc, adminToken);
        webhooks = new AsaasWebhooks(mvc);
        storedOrders = new StoredOrders(jdbc);
        storedEmails = new StoredOutboxEmails(jdbc);
        BffApi bff = new BffApi(mvc);
        studentEmail = StudentApi.newEmail();
        orders = new StudentOrders(bff, new StudentApi(bff).signedUp(studentEmail, PASSWORD));
    }

    @Test
    void aPaymentWhileAnOrdersEnrollmentIsActiveIsADuplicatePaymentThatGrantsNothing() {
        long course = courses.onSale(newSlug());
        String first = orders.placedPix(course, Cpfs.newCpf());
        confirm(first);
        String second = storedOrders.insertAwaitingCopyOf(first);

        confirm(second);
        List<String> queuedAlerts = storedEmails.recipientsOf(ALERT_TEMPLATE, second);
        sendOnlyTheEmailsTo(studentEmail);

        assertThat(queuedAlerts).contains(adminEmail);
        assertThat(orders.get(second)).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                {"code": "%s", "status": "PAID", "duplicatePayment": true}""".formatted(second));
        assertThat(orders.list()).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                {"items": [{"code": "%s", "status": "PAID", "duplicatePayment": true},
                           {"code": "%s", "status": "PAID", "duplicatePayment": false}]}""".formatted(second, first));
        assertThat(enrollments.list("email=" + studentEmail)).bodyJson().isLenientlyEqualTo("""
                {"items": [{"status": "ACTIVE", "origin": "ORDER", "orderCode": "%s", "course": {"id": %d}}],
                 "totalItems": 1}""".formatted(first, course));
        assertThat(purchaseEmails()).singleElement().satisfies(email -> assertThat(email.text()).contains(first));
        assertThat(storedEmails.recipientsOf(ALERT_TEMPLATE, first)).isEmpty();
    }

    @Test
    void aPaymentWhileAManualEnrollmentIsActiveIsADuplicatePaymentThatGrantsNothing() {
        long course = courses.onSale(newSlug());
        String code = orders.placedPix(course, Cpfs.newCpf());
        enrollments.granted(studentEmail, course);

        confirm(code);
        List<String> queuedAlerts = storedEmails.recipientsOf(ALERT_TEMPLATE, code);
        sendOnlyTheEmailsTo(studentEmail);

        assertThat(queuedAlerts).contains(adminEmail);
        assertThat(orders.get(code)).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                {"code": "%s", "status": "PAID", "duplicatePayment": true}""".formatted(code));
        assertThat(orders.list()).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                {"items": [{"code": "%s", "status": "PAID", "duplicatePayment": true}]}""".formatted(code));
        assertThat(enrollments.list("email=" + studentEmail)).bodyJson().isLenientlyEqualTo("""
                {"items": [{"status": "ACTIVE", "origin": "MANUAL", "course": {"id": %d}}], "totalItems": 1}"""
                .formatted(course));
        assertThat(purchaseEmails()).isEmpty();
    }

    @Test
    void emailsEveryAdminOneAlertNamingTheOrder() {
        String otherAdminEmail = newAdminEmail();
        accounts.createAdmin(otherAdminEmail, "Bruno", PASSWORD);
        long course = courses.onSale(newSlug());
        String code = orders.placedPix(course, Cpfs.newCpf());
        enrollments.granted(studentEmail, course);

        confirm(code);
        assertThat(storedEmails.recipientsOf(ALERT_TEMPLATE, code))
                .containsExactlyElementsOf(new StoredAccounts(jdbc).adminEmails());
        sendOnlyTheEmailsTo(adminEmail, otherAdminEmail);

        assertThat(alertsTo(adminEmail)).singleElement().satisfies(email -> {
            assertThat(email.from()).isEqualTo("AulaFlix <contato@aulaflix.com.br>");
            assertThat(email.to()).containsExactly(adminEmail);
            assertThat(email.subject()).isEqualTo("Pagamento duplicado: pedido " + code);
            assertThat(email.text()).contains("Olá, Ana!", "pedido " + code, "R$ 447,30", COURSE_TITLE,
                    "POST /v1/admin/orders/%s/refund".formatted(code));
            assertThat(email.text()).doesNotContain(studentEmail);
        });
        assertThat(alertsTo(otherAdminEmail)).singleElement().satisfies(email ->
                assertThat(email.text()).contains("Olá, Bruno!", "pedido " + code));
    }

    /** Asaas delivers at least once, and a Pix under a cautionary block goes CONFIRMED, then RECEIVED. */
    @Test
    void sendsNoSecondAlertHoweverManyTimesThePaymentIsConfirmed() {
        long course = courses.onSale(newSlug());
        String code = orders.placedPix(course, Cpfs.newCpf());
        enrollments.granted(studentEmail, course);
        String charge = chargeIs(code, "CONFIRMED");
        String confirmed = paymentEvent(newEventId(), "PAYMENT_CONFIRMED", charge, "CONFIRMED", PIX_PRICE_CENTS,
                code);
        webhooks.deliver(confirmed);
        worker.processPending();
        sendOnlyTheEmailsTo(adminEmail);
        chargeIs(code, "RECEIVED");

        webhooks.deliver(confirmed);
        webhooks.deliver(paymentEvent(newEventId(), "PAYMENT_RECEIVED", charge, "RECEIVED", PIX_PRICE_CENTS, code));
        webhooks.deliver(paymentEvent(newEventId(), "PAYMENT_CONFIRMED", charge, "CONFIRMED", PIX_PRICE_CENTS, code));
        worker.processPending();
        List<String> queuedAlerts = storedEmails.recipientsOf(ALERT_TEMPLATE, code);
        sendOnlyTheEmailsTo(adminEmail);

        assertThat(queuedAlerts).containsExactly(adminEmail);
        assertThat(alertsTo(adminEmail)).hasSize(1);
        assertThat(orders.get(code)).bodyJson().isLenientlyEqualTo("""
                {"status": "PAID", "duplicatePayment": true}""");
    }

    /** Asaas confirms the charge, then its webhook is delivered and the worker runs. */
    private void confirm(String code) {
        String charge = chargeIs(code, "CONFIRMED");
        assertThat(webhooks.deliver(paymentEvent(newEventId(), "PAYMENT_CONFIRMED", charge, "CONFIRMED",
                PIX_PRICE_CENTS, code))).hasStatusOk();
        worker.processPending();
    }

    private String chargeIs(String code, String status) {
        String charge = Asaas.chargeOf(code);
        asaas.chargeIs(charge, status, PIX_PRICE_CENTS, code, false);
        return charge;
    }

    /**
     * Sends what is queued to these addresses, and nothing else: the alert goes to every Admin the whole suite has
     * made, hundreds of them, so the stored queue shows that each got one, and Mailpit what the test's own received.
     */
    private void sendOnlyTheEmailsTo(String... recipients) {
        storedEmails.discardPendingExceptTo(recipients);
        outbox.drain();
    }

    private List<Mailpit.Email> purchaseEmails() {
        return mailpit.to(studentEmail).stream()
                .filter(email -> email.subject().equals("Compra confirmada: " + COURSE_TITLE))
                .toList();
    }

    private List<Mailpit.Email> alertsTo(String admin) {
        return mailpit.to(admin).stream()
                .filter(email -> email.subject().startsWith("Pagamento duplicado"))
                .toList();
    }

    private static String newAdminEmail() {
        return "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
    }
}
