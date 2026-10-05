package com.devlabs.aulaflix.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/** What the Student pays a Pix Order with, from their bank's app, until it expires. */
public record PixPayment(
        @Schema(description = "The QR code, a PNG in base64")
        String qrCodePng,

        @Schema(description = "The copy-and-paste code (Pix copia e cola)",
                example = "00020101021226820014br.gov.bcb.pix…")
        String copyPasteCode,

        @Schema(description = "When the Order expires, 30 minutes after it was placed")
        Instant expiresAt) {
}
