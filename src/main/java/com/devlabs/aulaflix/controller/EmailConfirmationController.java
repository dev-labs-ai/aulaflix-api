package com.devlabs.aulaflix.controller;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.devlabs.aulaflix.config.OpenApiConfiguration;
import com.devlabs.aulaflix.dto.AuthenticatedAccount;
import com.devlabs.aulaflix.dto.EmailConfirmationRequest;
import com.devlabs.aulaflix.service.EmailConfirmationService;

/**
 * The confirmation link, which sign-up sends as the welcome email: the Student follows it from any device, signed in
 * or not, and can ask for a new one. Nothing is gated on confirmation.
 */
@RestController
@RequestMapping("/v1")
@Tag(name = "Email confirmation", description = "Confirming a Student's email by link, and sending a new link")
public class EmailConfirmationController {

    private final EmailConfirmationService confirmations;

    public EmailConfirmationController(EmailConfirmationService confirmations) {
        this.confirmations = confirmations;
    }

    @PostMapping("/email-confirmations")
    @Operation(summary = "Confirm an email by its link", description = """
            Confirms the email of the link's Account, without a session, and signs no one in. A second click on the \
            same link says confirmed again, until the link expires 72 hours after it was sent. Each client IP gets 30 \
            an hour, whatever they answer.""")
    @ApiResponse(responseCode = "204", description = "Confirmed, now or earlier")
    @ApiResponse(responseCode = "400", description = """
            `invalid-request` with `errors`, or `invalid-confirmation-link` for a link that is unknown, expired, or \
            voided by a newer one""")
    public ResponseEntity<Void> confirm(@Valid @RequestBody EmailConfirmationRequest request) {
        confirmations.confirm(request.token());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/account/confirmation-emails")
    @PreAuthorize("hasRole('STUDENT')")
    @SecurityRequirement(name = OpenApiConfiguration.BEARER)
    @Operation(summary = "Send a new confirmation link", description = """
            Queues an email with a new link to the Student's email, and voids every earlier link. A new link waits 60 \
            seconds after the latest one, sign-up's included, and an Account gets at most 5 resends within 24 \
            hours.""")
    @ApiResponse(responseCode = "204", description = "A new link is on its way")
    @ApiResponse(responseCode = "409", description = "`email-already-confirmed`")
    @ApiResponse(responseCode = "429", description = """
            `rate-limited`, within 60 seconds of the latest link or past 5 resends within 24 hours, with \
            `Retry-After` in seconds""")
    public ResponseEntity<Void> resend(@AuthenticationPrincipal AuthenticatedAccount student) {
        confirmations.resend(student.accountId());
        return ResponseEntity.noContent().build();
    }
}
