package com.devlabs.aulaflix.service;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.devlabs.aulaflix.domain.entity.VerificationCodeKind;

/**
 * What a 6-digit code is stored as: its HMAC-SHA256 under the server's secret, since a plain hash of a million values
 * reverses offline. The Account and the kind go into it too, so that a code proves nothing for another Account or for
 * the other flow.
 */
public final class CodeHmac {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public CodeHmac(byte[] key) {
        this.key = new SecretKeySpec(key, ALGORITHM);
    }

    /** Lower-case hex, the form the table keeps. */
    String of(long accountId, VerificationCodeKind kind, String code) {
        return HexFormat.of().formatHex(mac().doFinal(
                "%d:%s:%s".formatted(accountId, kind, code).getBytes(StandardCharsets.UTF_8)));
    }

    /** Compares in constant time, so that the answer's timing tells nothing about how much of the code is right. */
    boolean matches(String storedHmac, long accountId, VerificationCodeKind kind, String code) {
        return MessageDigest.isEqual(storedHmac.getBytes(StandardCharsets.US_ASCII),
                of(accountId, kind, code).getBytes(StandardCharsets.US_ASCII));
    }

    /** A {@link Mac} holds state, so each use takes its own. */
    private Mac mac() {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac;
        } catch (NoSuchAlgorithmException | InvalidKeyException unsupported) {
            throw new IllegalStateException("Every Java runtime provides HmacSHA256", unsupported);
        }
    }
}
