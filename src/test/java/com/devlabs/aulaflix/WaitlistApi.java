package com.devlabs.aulaflix;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import tools.jackson.databind.json.JsonMapper;

/**
 * Joins and leaves Waitlists through the HTTP contract, the way the BFF does for one browser: by email for a Visitor,
 * and with the session's token for a Student. Course ids go out as given, so a test can send one of any shape.
 */
public final class WaitlistApi {

    public static final String ENTRIES = "/v1/waitlist-entries";

    public static final String UNSUBSCRIPTIONS = "/v1/waitlist-unsubscriptions";

    private static final Pattern UNSUBSCRIBE_LINK =
            Pattern.compile("http://localhost:3001/cancelar-aviso#([A-Za-z0-9_-]+)");

    private final BffApi bff;

    public WaitlistApi(BffApi bff) {
        this.bff = bff;
    }

    /** A Visitor joins by email. */
    public MvcTestResult join(Object courseId, String email) {
        return joinWith(JsonMapper.shared().writeValueAsString(Map.of("courseId", courseId, "email", email)));
    }

    public MvcTestResult joinWith(String body) {
        return bff.post(ENTRIES).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    /** Whether the Student is on the Course's Waitlist: 204 or 404. */
    public MvcTestResult check(String token, Object courseId) {
        return bff.get(ofStudent(courseId)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    /** The Student joins in one click, with the Account's email. */
    public MvcTestResult enter(String token, Object courseId) {
        return bff.put(ofStudent(courseId)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    public MvcTestResult leave(String token, Object courseId) {
        return bff.delete(ofStudent(courseId)).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    /** The page of the launch email's link, or the one-click unsubscribe, posts the token the same way. */
    public MvcTestResult unsubscribe(String token) {
        return unsubscribeWith(JsonMapper.shared().writeValueAsString(Map.of("token", token)));
    }

    public MvcTestResult unsubscribeWith(String body) {
        return bff.post(UNSUBSCRIPTIONS).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    public static String ofStudent(Object courseId) {
        return "/v1/account/waitlists/" + courseId;
    }

    /**
     * The token in the launch email's body link, {@code {webBase}/cancelar-aviso#<token>}, failing the test unless the
     * email carries exactly one such link. The tests' web base is the local one.
     */
    public static String unsubscribeTokenIn(Mailpit.Email email) {
        Matcher link = UNSUBSCRIBE_LINK.matcher(email.text());
        if (!link.find()) {
            throw new AssertionError("No unsubscribe link in: " + email.text());
        }
        String token = link.group(1);
        if (link.find()) {
            throw new AssertionError("More than one unsubscribe link in: " + email.text());
        }
        return token;
    }
}
