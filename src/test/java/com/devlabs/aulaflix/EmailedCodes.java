package com.devlabs.aulaflix;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the 6-digit code out of the emails a Student received, the way the Student would type it. */
public final class EmailedCodes {

    private static final Pattern CODE = Pattern.compile("(?<![0-9])([0-9]{6})(?![0-9])");

    private EmailedCodes() {
    }

    /** The code in the email, failing the test unless the email carries exactly one. */
    public static String codeIn(Mailpit.Email email) {
        Matcher code = CODE.matcher(email.text());
        if (!code.find()) {
            throw new AssertionError("No 6-digit code in: " + email.text());
        }
        String found = code.group(1);
        if (code.find()) {
            throw new AssertionError("More than one 6-digit code in: " + email.text());
        }
        return found;
    }

    /** The codes of the emails with the subject the address received, the newest first. */
    public static List<String> codesSentTo(Mailpit mailpit, String address, String subject) {
        return mailpit.to(address).stream()
                .filter(email -> email.subject().equals(subject))
                .map(EmailedCodes::codeIn)
                .toList();
    }
}
