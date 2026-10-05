package com.devlabs.aulaflix.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.service.AccountService;

/**
 * Only the BFF calls the API, so every request but the Admin's carries the BFF's key, and the browser's IP after it.
 * The Admin comes in through the SSH tunnel, with Swagger UI or curl, and has no key.
 */
class BffRequestFilterTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private AccountService accounts;

    @ParameterizedTest
    @ValueSource(strings = {"/v1/courses", "/v1/courses/backend-com-node-js", "/v1/nothing-here", "/nothing-here"})
    void refusesARequestWithoutTheKeyWhateverItAsksFor(String path) {
        MvcTestResult result = mvc.get().uri(path).header("AulaFlix-Client-IP", BffApi.newClientIp()).exchange();

        assertInvalidBffKey(result, path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "wrong", BffApi.KEY + "x", "x" + BffApi.KEY,
            "BFF-KEY-OF-THE-TESTS-0123456789ABCDEFGHIJ", "bff-key-of-the-tests-0123456789abcdefghi"})
    void refusesARequestWithAWrongKey(String key) {
        MvcTestResult result = mvc.get().uri("/v1/courses")
                .header("AulaFlix-BFF-Key", key)
                .header("AulaFlix-Client-IP", BffApi.newClientIp())
                .exchange();

        assertInvalidBffKey(result, "/v1/courses");
    }

    @Test
    void refusesAWrongKeyBeforeLookingAtTheSessionToken() {
        MvcTestResult result = mvc.get().uri("/v1/courses")
                .header("AulaFlix-BFF-Key", "wrong")
                .header("AulaFlix-Client-IP", BffApi.newClientIp())
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-session")
                .exchange();

        assertInvalidBffKey(result, "/v1/courses");
    }

    @Test
    void refusesAKeyedRequestWithoutTheBrowsersIp() {
        MvcTestResult result = mvc.get().uri("/v1/courses").header("AulaFlix-BFF-Key", BffApi.KEY).exchange();

        assertInvalidClientIp(result);
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"", " ", "localhost", "aulaflix.com.br", "203.0.113.7, 198.51.100.1", "203.0.113.7:443",
            " 203.0.113.7", "256.0.0.1", "203.0.113.7.1", "2001:db8::g", "unknown", "1", "127.1", "203.0.7",
            "4294967295", "017.0.0.1", "[2001:db8::1]", "fe80::1%1", "fe80::1%eth0"})
    void refusesAKeyedRequestWhoseClientIpIsNotAnIpAddress(String clientIp) {
        MvcTestResult result = mvc.get().uri("/v1/courses")
                .header("AulaFlix-BFF-Key", BffApi.KEY)
                .header("AulaFlix-Client-IP", clientIp)
                .exchange();

        assertInvalidClientIp(result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"203.0.113.7", "2001:db8::1", "2001:0db8:0000:0000:0000:0000:0000:0001", "::1",
            "::ffff:203.0.113.7"})
    void servesAKeyedRequestFromAnIpv4OrIpv6Address(String clientIp) {
        MvcTestResult result = mvc.get().uri("/v1/courses")
                .header("AulaFlix-BFF-Key", BffApi.KEY)
                .header("AulaFlix-Client-IP", clientIp)
                .exchange();

        assertThat(result).hasStatusOk().bodyJson().hasPath("$.items");
    }

    @Test
    void readsTheClientIpOnlyOnceTheKeyIsRight() {
        MvcTestResult result = mvc.get().uri("/v1/courses").header("AulaFlix-BFF-Key", "wrong").exchange();

        assertInvalidBffKey(result, "/v1/courses");
    }

    @Test
    void letsNoForwardedHeaderStandInForTheClientIp() {
        MvcTestResult result = mvc.get().uri("/v1/courses")
                .header("AulaFlix-BFF-Key", BffApi.KEY)
                .header("X-Forwarded-For", "203.0.113.7")
                .header("Forwarded", "for=203.0.113.7")
                .exchange();

        assertInvalidClientIp(result);
    }

    /** Spring's forwarded headers stay off: honoured, they would rewrite the request the answer is built from. */
    @Test
    void answersByteForByteTheSameWhateverTheForwardedHeadersSay() {
        String path = "/v1/courses/curso-" + UUID.randomUUID();
        MvcTestResult plain = new BffApi(mvc).get(path).exchange();

        MvcTestResult forwarded = new BffApi(mvc).get(path)
                .header("X-Forwarded-For", "198.51.100.1")
                .header("X-Forwarded-Host", "attacker.example")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Port", "8443")
                .header("X-Forwarded-Prefix", "/attacker")
                .header("Forwarded", "for=198.51.100.1;host=attacker.example;proto=https")
                .exchange();

        assertThat(forwarded).hasStatus(HttpStatus.NOT_FOUND).bodyJson().extractingPath("$.instance").isEqualTo(path);
        assertThat(forwarded.getResponse().getContentAsByteArray())
                .isEqualTo(plain.getResponse().getContentAsByteArray());
        assertThat(forwarded.getResponse().getHeaderNames()).isEqualTo(plain.getResponse().getHeaderNames());
    }

    @Test
    void servesTheAdminWithoutTheKey() {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(email, "Ana", PASSWORD);
        String token = new AdminApi(mvc).sessionToken(email, PASSWORD);

        assertThat(mvc.get().uri("/v1/admin/courses").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .hasStatusOk();
        assertThat(mvc.get().uri("/v1/admin/courses")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/v3/api-docs/admin")).hasStatusOk();
        assertThat(mvc.get().uri("/swagger-ui/index.html")).hasStatusOk();
    }

    private void assertInvalidClientIp(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-client-ip",
                          "title": "Invalid client IP",
                          "status": 400,
                          "detail": "The BFF sends the browser's IP address as AulaFlix-Client-IP.",
                          "instance": "/v1/courses",
                          "timestamp": "%s"
                        }""".formatted(clock.instant()));
    }

    private void assertInvalidBffKey(MvcTestResult result, String path) {
        assertThat(result).hasStatus(HttpStatus.FORBIDDEN)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-bff-key",
                          "title": "Invalid BFF key",
                          "status": 403,
                          "detail": "Only the AulaFlix web server calls this API, with its AulaFlix-BFF-Key.",
                          "instance": "%s",
                          "timestamp": "%s"
                        }""".formatted(path, clock.instant()));
    }
}
