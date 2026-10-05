package com.devlabs.aulaflix.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The key the launch email's unsubscribe tokens are sealed under, read from the secret file
 * {@code aulaflix.waitlist.unsubscribe-key}: 32 random bytes in base64, as {@code openssl rand -base64 32} writes them.
 * It must survive every redeploy, since a token never expires: a new key breaks every link already sent.
 */
@Validated
@ConfigurationProperties("aulaflix.waitlist")
public record WaitlistProperties(
        @NotNull
        @Pattern(regexp = "[A-Za-z0-9+/]{43}=", message = "must be 32 bytes in base64")
        String unsubscribeKey) {

    /** Keeps the key out of anything that prints the properties. */
    @Override
    public String toString() {
        return "WaitlistProperties[unsubscribeKey=<hidden>]";
    }
}
