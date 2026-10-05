package com.devlabs.aulaflix;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.jayway.jsonpath.JsonPath;

/**
 * Asaas's API, played by WireMock so that no test reaches the sandbox. By default it takes every customer and charge:
 * a customer's id is {@code cus_} and its CPF in base64, a charge's id is {@code pay_} and its external reference,
 * which is the Order's code, and every Pix QR code is {@link #QR_CODE_PNG} with a copy-and-paste code ending in the
 * charge's id. A search by external reference finds the charge made under it. Since every test shares the server, a
 * test that wants another answer asks for it by something only it uses, its Student's CPF or the customer that CPF
 * makes, and gets it once.
 */
public final class Asaas {

    /** How long the tests' application waits for Asaas, short so that a test can outwait it. */
    public static final Duration TIMEOUT = Duration.ofSeconds(1);

    /** The key the tests' application sends, as a sandbox key looks. */
    public static final String API_KEY = "$aact_hmlg_key-of-the-tests";

    /** The token Asaas sends with every webhook delivery, as the tests' application expects it. */
    public static final String WEBHOOK_TOKEN = "webhook-token-of-the-tests-0123456789";

    /** A 1×1 PNG, base64, as Asaas sends a Pix QR code's image. */
    public static final String QR_CODE_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAAAAAA6fptVAAAACklEQVR4nGNgAAAAAgABc3UBGAAAAABJRU5ErkJggg==";

    private static final String COPY_PASTE_PREFIX = "00020101021226820014br.gov.bcb.pix2560qrpix.example/";
    private static final int LOWEST_PRIORITY = 10;
    private static final String TEMPLATE = "response-template";

    private final WireMockServer server = new WireMockServer(options().dynamicPort());

    public Asaas() {
        server.start();
        server.stubFor(post(urlPathEqualTo("/v3/customers")).atPriority(LOWEST_PRIORITY)
                .willReturn(okJson("""
                        {"object": "customer", "id": "cus_{{base64 (jsonPath request.body '$.cpfCnpj') padding=false}}",
                         "name": "{{jsonPath request.body '$.name'}}", "notificationDisabled": true}""")
                        .withTransformers(TEMPLATE)));
        server.stubFor(post(urlPathEqualTo("/v3/payments")).atPriority(LOWEST_PRIORITY)
                .willReturn(okJson("""
                        {"object": "payment", "id": "pay_{{jsonPath request.body '$.externalReference'}}",
                         "customer": "{{jsonPath request.body '$.customer'}}", "billingType": "PIX",
                         "status": "PENDING", "value": {{jsonPath request.body '$.value'}},
                         "externalReference": "{{jsonPath request.body '$.externalReference'}}", "deleted": false}""")
                        .withTransformers(TEMPLATE)));
        server.stubFor(get(urlPathMatching("/v3/payments/[^/]+/pixQrCode")).atPriority(LOWEST_PRIORITY)
                .willReturn(okJson("""
                        {"encodedImage": "%s", "payload": "%s{{request.pathSegments.[2]}}",
                         "expirationDate": "2027-10-05 23:59:59", "description": null}"""
                        .formatted(QR_CODE_PNG, COPY_PASTE_PREFIX))
                        .withTransformers(TEMPLATE)));
        server.stubFor(get(urlPathEqualTo("/v3/payments")).atPriority(LOWEST_PRIORITY)
                .willReturn(okJson("""
                        {"object": "list", "hasMore": false, "totalCount": 1, "limit": 10, "offset": 0,
                         "data": [{"object": "payment", "id": "pay_{{request.query.externalReference}}",
                                   "externalReference": "{{request.query.externalReference}}",
                                   "status": "PENDING", "deleted": false}]}""")
                        .withTransformers(TEMPLATE)));
        server.stubFor(delete(urlPathMatching("/v3/payments/[^/]+")).atPriority(LOWEST_PRIORITY)
                .willReturn(okJson("""
                        {"deleted": true, "id": "{{request.pathSegments.[2]}}"}""").withTransformers(TEMPLATE)));
        server.stubFor(post(urlPathMatching("/v3/payments/[^/]+/refund")).atPriority(LOWEST_PRIORITY)
                .willReturn(okJson("""
                        {"object": "payment", "id": "{{request.pathSegments.[2]}}", "status": "REFUND_REQUESTED",
                         "deleted": false,
                         "refunds": [{"dateCreated": "2026-10-05 14:45:03", "status": "PENDING"}]}""")
                        .withTransformers(TEMPLATE)));
    }

    /** The {@code aulaflix.asaas.*} properties that point the API at this server. */
    public Map<String, Supplier<Object>> applicationProperties() {
        Map<String, Supplier<Object>> properties = new LinkedHashMap<>();
        properties.put("aulaflix.asaas.base-url", () -> server.baseUrl() + "/v3");
        properties.put("aulaflix.asaas.api-key", () -> API_KEY);
        properties.put("aulaflix.asaas.timeout", TIMEOUT::toString);
        properties.put("aulaflix.asaas.webhook-token", () -> WEBHOOK_TOKEN);
        return properties;
    }

    public void stop() {
        server.stop();
    }

    /** The id the default answer gives the customer made with the CPF: derived from it, but never holding it. */
    public static String customerOf(String cpf) {
        return "cus_" + Base64.getEncoder().withoutPadding().encodeToString(cpf.getBytes(StandardCharsets.US_ASCII));
    }

    /** The id the default answer gives the charge made under the Order's code. */
    public static String chargeOf(String orderCode) {
        return "pay_" + orderCode;
    }

    /** The copy-and-paste code the default answer gives the charge's QR code. */
    public static String copyPasteCodeOf(String chargeId) {
        return COPY_PASTE_PREFIX + chargeId;
    }

    /** Answers the next creation of a customer with the CPF this way: with a fault, an error status, too late, … */
    public void answerNextCustomerWith(String cpf, ResponseDefinitionBuilder answer) {
        server.stubFor(once("customer " + cpf, post(urlPathEqualTo("/v3/customers"))
                .withRequestBody(matchingJsonPath("$.cpfCnpj", equalTo(cpf))))
                .willReturn(answer));
    }

    /** Answers the next creation of a charge for the customer this way. */
    public void answerNextChargeFor(String customerId, ResponseDefinitionBuilder answer) {
        server.stubFor(once("charge " + customerId, post(urlPathEqualTo("/v3/payments"))
                .withRequestBody(matchingJsonPath("$.customer", equalTo(customerId))))
                .willReturn(answer));
    }

    /**
     * Gives the next charge for the customer the id, then answers its QR code this way: the id is all the QR code's
     * request carries.
     */
    public void answerNextQrCodeFor(String customerId, String chargeId, ResponseDefinitionBuilder answer) {
        answerNextChargeFor(customerId, okJson("""
                {"object": "payment", "id": "%s", "status": "PENDING", "deleted": false}""".formatted(chargeId)));
        server.stubFor(once("QR code " + chargeId,
                get(urlPathEqualTo("/v3/payments/%s/pixQrCode".formatted(chargeId)))).willReturn(answer));
    }

    /** Answers the next search for charges by external reference this way, whoever makes it. */
    public void answerNextChargeSearchWith(ResponseDefinitionBuilder answer) {
        server.stubFor(once("search " + UUID.randomUUID(), get(urlPathEqualTo("/v3/payments"))).willReturn(answer));
    }

    /**
     * Answers every read of the charge as Asaas would show it: in the status, for the value in cents, under the
     * external reference, and deleted or not. A test's charge is its own, since its id is made from its Order's code.
     */
    public void chargeIs(String chargeId, String status, int valueCents, String externalReference, boolean deleted) {
        BigDecimal value = BigDecimal.valueOf(valueCents, 2);
        answerChargeReadsWith(chargeId, okJson("""
                {"object": "payment", "id": "%s", "customer": "cus_000000000001", "billingType": "PIX",
                 "status": "%s", "value": %s, "netValue": %s, "externalReference": "%s", "deleted": %s,
                 "dateCreated": "2026-10-05", "dueDate": "2026-10-05", "description": null}"""
                .formatted(chargeId, status, value, value, externalReference, deleted)));
    }

    /**
     * Answers every read of the charge as Asaas shows it once refunded: {@code REFUNDED}, with the one refund in its
     * own status, {@code PENDING} until it is {@code DONE}.
     */
    public void chargeIsRefunded(String chargeId, String refundStatus, int valueCents, String externalReference) {
        BigDecimal value = BigDecimal.valueOf(valueCents, 2);
        answerChargeReadsWith(chargeId, okJson("""
                {"object": "payment", "id": "%s", "customer": "cus_000000000001", "billingType": "PIX",
                 "status": "REFUNDED", "value": %s, "netValue": %s, "externalReference": "%s", "deleted": false,
                 "refunds": [{"dateCreated": "2026-10-05 14:45:03", "status": "%s", "value": %s,
                              "description": null}]}"""
                .formatted(chargeId, value, value, externalReference, refundStatus, value)));
    }

    /**
     * Answers every read of the charge as Asaas shows it after money went back: in the status, with whatever else the
     * charge then holds, given as JSON members, such as its {@code refunds} or its {@code chargeback}, or nothing.
     */
    public void chargeShows(String chargeId, String status, int valueCents, String externalReference,
                            String members) {
        BigDecimal value = BigDecimal.valueOf(valueCents, 2);
        answerChargeReadsWith(chargeId, okJson("""
                {"object": "payment", "id": "%s", "customer": "cus_000000000001", "billingType": "PIX",
                 "status": "%s", "value": %s, "netValue": %s, "externalReference": "%s", "deleted": false%s}"""
                .formatted(chargeId, status, value, value, externalReference,
                        members.isEmpty() ? "" : ", " + members)));
    }

    /** Answers every read of the charge this way: with a fault, an error status, too late, … */
    public void answerChargeReadsWith(String chargeId, ResponseDefinitionBuilder answer) {
        server.stubFor(get(urlPathEqualTo("/v3/payments/" + chargeId)).willReturn(answer));
    }

    /** Answers the next read of the charge this way, then as before. */
    public void answerNextChargeReadWith(String chargeId, ResponseDefinitionBuilder answer) {
        server.stubFor(once("read " + chargeId, get(urlPathEqualTo("/v3/payments/" + chargeId))).atPriority(1)
                .willReturn(answer));
    }

    /** Answers every search for charges under the external reference this way. */
    public void answerChargeSearchesUnder(String externalReference, ResponseDefinitionBuilder answer) {
        server.stubFor(get(urlPathEqualTo("/v3/payments"))
                .withQueryParam("externalReference", equalTo(externalReference)).willReturn(answer));
    }

    /** Answers the next deletion of the charge this way, then as before. */
    public void answerNextDeletionOf(String chargeId, ResponseDefinitionBuilder answer) {
        server.stubFor(once("deletion " + chargeId, delete(urlPathEqualTo("/v3/payments/" + chargeId)))
                .willReturn(answer));
    }

    /** Answers the next refund of the charge this way, then as before. */
    public void answerNextRefundOf(String chargeId, ResponseDefinitionBuilder answer) {
        server.stubFor(once("refund " + chargeId,
                post(urlPathEqualTo("/v3/payments/%s/refund".formatted(chargeId)))).willReturn(answer));
    }

    /** How many times the charge was refunded. */
    public int refundsOf(String chargeId) {
        return server.findAll(postRequestedFor(urlPathEqualTo("/v3/payments/%s/refund".formatted(chargeId)))).size();
    }

    /** The bodies of the refunds of the charge, in the order they were sent. */
    public List<String> refundRequestsOf(String chargeId) {
        return bodies(server.findAll(postRequestedFor(urlPathEqualTo("/v3/payments/%s/refund".formatted(chargeId)))));
    }

    /** How many times a charge was read by no id at all, as a read of a charge the API never got the id of would be. */
    public int readsWithoutAChargeId() {
        return server.findAll(getRequestedFor(urlPathMatching("/v3/payments/(null)?"))).size();
    }

    /** How many times the charge was read. */
    public int readsOf(String chargeId) {
        return server.findAll(getRequestedFor(urlPathEqualTo("/v3/payments/" + chargeId))).size();
    }

    /** Matches only until it has answered once; then the default answers again. */
    private static MappingBuilder once(String scenario, MappingBuilder request) {
        return request.inScenario(scenario).whenScenarioStateIs(Scenario.STARTED).willSetStateTo("answered");
    }

    /** An answer that comes after the application stopped waiting. */
    public static ResponseDefinitionBuilder tooLate() {
        return okJson("{}").withFixedDelay((int) TIMEOUT.multipliedBy(2).toMillis());
    }

    /** An error as Asaas words one, with its description. */
    public static ResponseDefinitionBuilder error(int status, String code, String description) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody("""
                {"errors": [{"code": "%s", "description": "%s"}]}""".formatted(code, description));
    }

    /** An error as Asaas words one: {@code {"errors": [{"code", "description"}]}}. */
    public static ResponseDefinitionBuilder error(int status, String code) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody("""
                {"errors": [{"code": "%s", "description": "Descrição do erro"}]}""".formatted(code));
    }

    /** The bodies of the customers created with the CPF, in the order they were sent. */
    public List<String> customersCreatedWith(String cpf) {
        return bodies(server.findAll(postRequestedFor(urlPathEqualTo("/v3/customers"))
                .withRequestBody(matchingJsonPath("$.cpfCnpj", equalTo(cpf)))));
    }

    /** The bodies of the charges created for the customer, in the order they were sent. */
    public List<String> chargesCreatedFor(String customerId) {
        return bodies(server.findAll(postRequestedFor(urlPathEqualTo("/v3/payments"))
                .withRequestBody(matchingJsonPath("$.customer", equalTo(customerId)))));
    }

    /** How many times the charge was deleted. */
    public int deletionsOf(String chargeId) {
        return server.findAll(deleteRequestedFor(urlPathEqualTo("/v3/payments/" + chargeId))).size();
    }

    /** The requests that created the customer's charges, headers included. */
    public List<LoggedRequest> chargeRequestsFor(String customerId) {
        return server.findAll(postRequestedFor(urlPathEqualTo("/v3/payments"))
                .withRequestBody(matchingJsonPath("$.customer", equalTo(customerId))));
    }

    /** A field of a request body, read with a JSON path. */
    public static Object field(String body, String path) {
        return JsonPath.read(body, path);
    }

    private static List<String> bodies(List<LoggedRequest> requests) {
        return requests.stream().map(LoggedRequest::getBodyAsString).toList();
    }
}
