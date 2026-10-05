package com.devlabs.aulaflix.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.password.CompromisedPasswordChecker;
import org.springframework.security.authentication.password.CompromisedPasswordDecision;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * The breached-password check, over HIBP's range API: only the first 5 hex digits of the password's SHA-1 ever leave
 * the API, and HIBP answers with the suffixes of every breached password that starts with them. It guards the quality
 * of a password, not access, so when HIBP cannot answer in time the password is taken unchecked, with a WARN. Spring
 * Security's own checker logs that at ERROR, with the URL that holds those digits.
 */
public class HibpPasswordChecker implements CompromisedPasswordChecker {

    private static final Logger log = LoggerFactory.getLogger(HibpPasswordChecker.class);

    private static final int PREFIX_LENGTH = 5;

    private final RestClient hibp;

    /** The client's base URL is HIBP's, and its timeouts bound how long a new password waits for the check. */
    public HibpPasswordChecker(RestClient hibp) {
        this.hibp = hibp;
    }

    @Override
    public CompromisedPasswordDecision check(String password) {
        String hash = sha1(password);
        try {
            String range = hibp.get().uri("/range/{prefix}", hash.substring(0, PREFIX_LENGTH))
                    .retrieve()
                    .body(String.class);
            return new CompromisedPasswordDecision(listsTheSuffix(range, hash.substring(PREFIX_LENGTH)));
        } catch (RestClientException failure) {
            log.warn("Took a new password without the breached-password check, which failed: {}", reason(failure));
            return new CompromisedPasswordDecision(false);
        }
    }

    /** One {@code SUFFIX:COUNT} per line. */
    private static boolean listsTheSuffix(String range, String suffix) {
        return range != null && range.lines().anyMatch(line -> line.regionMatches(true, 0, suffix + ":", 0,
                suffix.length() + 1));
    }

    /** The status, or the failure underneath; never the URL, which holds the start of the password's hash. */
    private static String reason(RestClientException failure) {
        return failure instanceof RestClientResponseException response
                ? "HTTP " + response.getStatusCode().value()
                : failure.getMostSpecificCause().toString();
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
