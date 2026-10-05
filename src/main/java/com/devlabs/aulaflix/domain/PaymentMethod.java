package com.devlabs.aulaflix.domain;

/**
 * How an Order is paid (ADR 0006). Pix is a direct charge shown on AulaFlix's page; a card is paid on an Asaas
 * Checkout, Asaas's own page, so that the card never touches AulaFlix.
 */
public enum PaymentMethod {
    PIX,
    CARD
}
