package com.devlabs.aulaflix.service;

import java.util.Map;

import com.devlabs.aulaflix.domain.entity.EmailTemplate;

/**
 * An email rendered from one of the outbox's templates, ready to queue: its plain-text body and any headers of its
 * own, such as {@code List-Unsubscribe}. The From is the outbox's, the same for every email.
 */
public record OutboundEmail(EmailTemplate template, String recipient, String subject, String body,
                            Map<String, String> headers) {

    public OutboundEmail {
        headers = Map.copyOf(headers);
    }
}
