package com.devlabs.aulaflix.dto;

/** Who a Bearer token belongs to: the Account, its role ({@code ADMIN} or {@code STUDENT}) and the session. */
public record AuthenticatedAccount(long accountId, String role, long sessionId) {
}
