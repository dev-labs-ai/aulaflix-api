package com.devlabs.aulaflix;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the confirmation link out of the emails a Student received, the way the Student would follow it. */
public final class ConfirmationLinks {

    /** The tests' web base is the local one; the token is the fragment, which never reaches a server's logs. */
    public static final Pattern LINK = Pattern.compile("http://localhost:3001/confirmar-email#([A-Za-z0-9_-]{43})");

    private ConfirmationLinks() {
    }

    /** The token in the email's link, failing the test unless the email carries exactly one link. */
    public static String tokenIn(Mailpit.Email email) {
        Matcher link = LINK.matcher(email.text());
        if (!link.find()) {
            throw new AssertionError("No confirmation link in: " + email.text());
        }
        String token = link.group(1);
        if (link.find()) {
            throw new AssertionError("More than one confirmation link in: " + email.text());
        }
        return token;
    }

    /** The tokens of the links the address received, the newest first. */
    public static List<String> tokensSentTo(Mailpit mailpit, String address) {
        return mailpit.to(address).stream().map(ConfirmationLinks::tokenIn).toList();
    }
}
