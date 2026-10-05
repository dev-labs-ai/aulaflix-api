package com.devlabs.aulaflix.service;

import java.net.InetAddress;
import java.time.Duration;

import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.devlabs.aulaflix.exception.CaptchaUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The Turnstile adapter: a thin {@link RestClient} over Cloudflare's {@code siteverify}, which holds the secret alone and
 * sends the visitor's IP along with the token. Turnstile itself refuses a token older than 5 minutes or verified
 * before, so each one lets a single request through. When {@code siteverify} cannot answer, nothing is let through.
 */
public class TurnstileVerifier {

    private final RestClient turnstile;
    private final String secretKey;
    private final Duration retryAfter;

    /**
     * The client's base URL is Turnstile's, up to {@code /v0}, and its timeouts bound how long a request past a soft
     * limit waits. A verification Turnstile could not answer is worth retrying after {@code retryAfter}.
     */
    public TurnstileVerifier(RestClient turnstile, String secretKey, Duration retryAfter) {
        this.turnstile = turnstile;
        this.secretKey = secretKey;
        this.retryAfter = retryAfter;
    }

    /** Whether Turnstile takes the token as a CAPTCHA the visitor at that IP solved, and has not taken it before. */
    boolean accepts(String token, InetAddress remoteIp) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", secretKey);
        form.add("response", token);
        form.add("remoteip", remoteIp.getHostAddress());
        Verdict verdict;
        try {
            verdict = turnstile.post().uri("/siteverify")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Verdict.class);
        } catch (RestClientException failure) {
            throw new CaptchaUnavailableException("Turnstile's siteverify failed: " + reason(failure), retryAfter);
        }
        if (verdict == null) {
            throw new CaptchaUnavailableException("Turnstile's siteverify answered nothing", retryAfter);
        }
        return verdict.success();
    }

    /** The status, or the failure underneath; never the request, which holds the secret and the token. */
    private static String reason(RestClientException failure) {
        return failure instanceof RestClientResponseException response
                ? "HTTP " + response.getStatusCode().value()
                : failure.getMostSpecificCause().toString();
    }

    /** What {@code siteverify} answers; only {@code success} decides. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Verdict(boolean success) {
    }
}
