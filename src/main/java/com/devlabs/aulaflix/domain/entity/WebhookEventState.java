package com.devlabs.aulaflix.domain.entity;

/**
 * A webhook event waits until the worker settles it: processed once its charge's re-read is applied, ignored when
 * there was nothing to apply, unprocessable when the body was no event or the re-read refused or contradicted it.
 */
public enum WebhookEventState {
    PENDING,
    PROCESSED,
    IGNORED,
    UNPROCESSABLE
}
