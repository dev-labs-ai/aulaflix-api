package com.devlabs.aulaflix;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;

/**
 * HIBP's range API, played by WireMock so that no test reaches the internet. Every range lists only passwords no test
 * uses, unless a test says that a password was breached, or how its range answers.
 */
public final class Hibp {

    /** How long the tests' application waits for a range, short so that a test can outwait it. */
    public static final Duration TIMEOUT = Duration.ofSeconds(1);

    /** Suffixes of two SHA-1 hashes, in HIBP's format: 35 upper-case hex digits, a colon, the times seen. */
    private static final String OTHER_PASSWORDS = """
            0018A45C4D1DEF81644B54AB7F969B88D65:10\r
            00D4F6E8FA6EECAD2A3AA415EEC418D38EC:2\r
            """;
    private static final int PREFIX_LENGTH = 5;
    private static final int LOWEST_PRIORITY = 10;

    private final WireMockServer server = new WireMockServer(options().dynamicPort());

    public Hibp() {
        server.start();
        server.stubFor(get(urlPathMatching("/range/[0-9A-F]{5}")).atPriority(LOWEST_PRIORITY)
                .willReturn(ok(OTHER_PASSWORDS)));
    }

    /** The {@code aulaflix.hibp.*} properties that point the API at this server. */
    public Map<String, Supplier<Object>> applicationProperties() {
        Map<String, Supplier<Object>> properties = new LinkedHashMap<>();
        properties.put("aulaflix.hibp.base-url", server::baseUrl);
        properties.put("aulaflix.hibp.timeout", TIMEOUT::toString);
        return properties;
    }

    public void stop() {
        server.stop();
    }

    /** Lists the password in its range, as HIBP does once the password has been seen in a breach. */
    public void breach(String password) {
        answer(password, ok(OTHER_PASSWORDS + sha1(password).substring(PREFIX_LENGTH) + ":3\r\n"));
    }

    /** Lists, in the password's range, only a suffix one digit away from its own, in the last place. */
    public void breachANeighbourOf(String password) {
        String suffix = sha1(password).substring(PREFIX_LENGTH);
        char last = suffix.charAt(suffix.length() - 1);
        String neighbour = suffix.substring(0, suffix.length() - 1) + (last == '0' ? '1' : '0');
        answer(password, ok(OTHER_PASSWORDS + neighbour + ":3\r\n"));
    }

    /** Answers the password's range this way: with a fault, an error status, too late, … */
    public void answer(String password, ResponseDefinitionBuilder answer) {
        server.stubFor(get(urlPathEqualTo("/range/" + sha1(password).substring(0, PREFIX_LENGTH)))
                .willReturn(answer));
    }

    private static String sha1(String password) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(password.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (NoSuchAlgorithmException unsupported) {
            throw new IllegalStateException("Every Java runtime provides SHA-1", unsupported);
        }
    }
}
