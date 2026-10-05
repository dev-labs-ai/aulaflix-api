package com.devlabs.aulaflix.service;

import java.util.List;
import java.util.regex.Pattern;

import com.devlabs.aulaflix.exception.FieldViolation;
import com.devlabs.aulaflix.exception.InvalidRequestException;

/**
 * A CPF's checks, by the Receita Federal's rule: 11 digits, the last two being check digits over the ones before. Each
 * check digit is the weighted sum of those digits modulo 11, where a remainder under 2 gives 0 and any other gives 11
 * minus it. Eleven times one digit passes that rule, and is refused all the same.
 */
final class Cpf {

    private static final String FIELD = "cpf";
    private static final Pattern PUNCTUATION = Pattern.compile("[.\\-\\s]");
    private static final Pattern ELEVEN_DIGITS = Pattern.compile("[0-9]{11}");
    private static final int BASE_LENGTH = 9;

    private Cpf() {
    }

    /** The CPF's 11 digits, its dots, dash and spaces dropped; a 400 when it is missing or not a valid CPF. */
    static String requireValid(String cpf) {
        if (cpf == null) {
            throw refusal("required");
        }
        String digits = PUNCTUATION.matcher(cpf).replaceAll("");
        if (!isValid(digits)) {
            throw refusal("invalid-cpf");
        }
        return digits;
    }

    /** Whether the 11 digits, without punctuation, are a CPF. */
    static boolean isValid(String digits) {
        return ELEVEN_DIGITS.matcher(digits).matches()
                && digits.chars().distinct().count() > 1
                && checkDigit(digits, BASE_LENGTH) == digits.charAt(BASE_LENGTH) - '0'
                && checkDigit(digits, BASE_LENGTH + 1) == digits.charAt(BASE_LENGTH + 1) - '0';
    }

    /** The check digit over the first {@code length} digits, weighted from {@code length + 1} down to 2. */
    private static int checkDigit(String digits, int length) {
        int sum = 0;
        for (int i = 0; i < length; i++) {
            sum += (digits.charAt(i) - '0') * (length + 1 - i);
        }
        int remainder = sum % 11;
        return remainder < 2 ? 0 : 11 - remainder;
    }

    private static InvalidRequestException refusal(String code) {
        return new InvalidRequestException(List.of(new FieldViolation(FIELD, code)));
    }
}
