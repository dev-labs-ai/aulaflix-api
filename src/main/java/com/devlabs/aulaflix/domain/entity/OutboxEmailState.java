package com.devlabs.aulaflix.domain.entity;

/** An outbox email waits, through any number of failed attempts, until it is sent. */
public enum OutboxEmailState {
    PENDING,
    SENT
}
