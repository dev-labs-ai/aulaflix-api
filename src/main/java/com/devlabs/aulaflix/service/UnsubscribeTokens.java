package com.devlabs.aulaflix.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The token of a launch email's unsubscribe links: the recipient's normalized email under AES-256-GCM, as base64url
 * without padding, so that it goes into a URL as it is. It never expires, and the API keeps no record of it: whoever
 * holds the key can open it, and any change to it fails GCM's tag. Each token takes a fresh random nonce, which it
 * carries before the ciphertext.
 */
public final class UnsubscribeTokens {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    /** Binds every token to this one use, so that nothing else sealed under the same key ever opens as one. */
    private static final byte[] PURPOSE = "aulaflix waitlist unsubscribe".getBytes(StandardCharsets.US_ASCII);

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    /** An AES-256 key, 32 bytes, and nothing shorter. */
    public UnsubscribeTokens(byte[] key) {
        if (key.length != KEY_BYTES) {
            throw new IllegalArgumentException("The unsubscribe key must be 32 bytes");
        }
        this.key = new SecretKeySpec(key, "AES");
    }

    String of(String email) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] sealed = cipher(Cipher.ENCRYPT_MODE, nonce, email.getBytes(StandardCharsets.UTF_8), 0);
        return ENCODER.encodeToString(ByteBuffer.allocate(NONCE_BYTES + sealed.length).put(nonce).put(sealed).array());
    }

    /**
     * The email the token was made for, or nothing for anything this key did not make. Only the canonical spelling is
     * taken: base64url leaves a few bits of the last character unused, and a token whose spare bits differ is not
     * the token.
     */
    Optional<String> emailIn(String token) {
        if (token == null) {
            return Optional.empty();
        }
        byte[] bytes;
        try {
            bytes = DECODER.decode(token);
        } catch (IllegalArgumentException notBase64Url) {
            return Optional.empty();
        }
        if (bytes.length < NONCE_BYTES + TAG_BITS / 8 || !ENCODER.encodeToString(bytes).equals(token)) {
            return Optional.empty();
        }
        try {
            byte[] email = cipher(Cipher.DECRYPT_MODE, bytes, bytes, NONCE_BYTES);
            return Optional.of(new String(email, StandardCharsets.UTF_8));
        } catch (TamperedTokenException tampered) {
            return Optional.empty();
        }
    }

    /** Seals or opens {@code input} from {@code offset} on, under the first 12 bytes of {@code nonce}. */
    private byte[] cipher(int mode, byte[] nonce, byte[] input, int offset) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce, 0, NONCE_BYTES));
            cipher.updateAAD(PURPOSE);
            return cipher.doFinal(input, offset, input.length - offset);
        } catch (AEADBadTagException tampered) {
            throw new TamperedTokenException();
        } catch (GeneralSecurityException unsupported) {
            throw new IllegalStateException("Every Java runtime provides AES-GCM", unsupported);
        }
    }

    private static final class TamperedTokenException extends RuntimeException {

        TamperedTokenException() {
            super(null, null, false, false);
        }
    }
}
