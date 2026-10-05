package com.devlabs.aulaflix;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CPFs for the tests, with their check digits worked out here the way the Receita Federal defines them: each is the
 * weighted sum of the digits before it, modulo 11, where a remainder under 2 gives 0 and any other gives 11 minus it.
 */
public final class Cpfs {

    private static final long BASES = 1_000_000_000L;
    private static final AtomicLong NEXT_BASE = new AtomicLong(ThreadLocalRandom.current().nextLong(BASES));

    private Cpfs() {
    }

    /** A valid CPF, 11 digits without punctuation, that no other test of this run has used. */
    public static String newCpf() {
        String cpf;
        do {
            cpf = withCheckDigits("%09d".formatted(Math.floorMod(NEXT_BASE.getAndIncrement(), BASES)));
        } while (cpf.chars().distinct().count() == 1);
        return cpf;
    }

    /** The 9 digits followed by their two check digits. */
    public static String withCheckDigits(String nineDigits) {
        String ten = nineDigits + checkDigit(nineDigits);
        return ten + checkDigit(ten);
    }

    /** The CPF written as people write it: {@code 123.456.789-09}. */
    public static String punctuated(String cpf) {
        return "%s.%s.%s-%s".formatted(cpf.substring(0, 3), cpf.substring(3, 6), cpf.substring(6, 9),
                cpf.substring(9));
    }

    private static int checkDigit(String digits) {
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            sum += (digits.charAt(i) - '0') * (digits.length() + 1 - i);
        }
        int remainder = sum % 11;
        return remainder < 2 ? 0 : 11 - remainder;
    }
}
