package com.devlabs.aulaflix.service;

import java.security.SecureRandom;

/**
 * Order codes: 8 characters from an alphabet without the ones people confuse when they read a code aloud or type it
 * (0 and O, 1, I and L, and U and V). 29 to the 8th power, about 5 × 10¹¹ codes, leaves a collision, which the unique
 * index would refuse, unlikely for as long as AulaFlix sells.
 */
final class OrderCodes {

    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTWXYZ";
    private static final int LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private OrderCodes() {
    }

    static String next() {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
