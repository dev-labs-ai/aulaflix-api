package com.devlabs.aulaflix.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The opaque tokens a holder proves something with, a session or a confirmation link: 256 random bits, base64url, of
 * which only the SHA-256 is ever stored.
 */
final class SecretTokens {

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private SecretTokens() {
    }

    static String newToken() {
        byte[] token = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    /** Lower-case hex, the form the tables keep. */
    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException unsupported) {
            throw new IllegalStateException("Every Java runtime provides SHA-256", unsupported);
        }
    }
}
