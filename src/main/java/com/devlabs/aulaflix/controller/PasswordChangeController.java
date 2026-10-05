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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.devlabs.aulaflix.config.OpenApiConfiguration;
import com.devlabs.aulaflix.dto.AuthenticatedAccount;
import com.devlabs.aulaflix.dto.PasswordChangeRequest;
import com.devlabs.aulaflix.service.PasswordChangeService;

/**
 * A signed-in Student's new password: a 6-digit code to their email, then the new password with it, which keeps this
 * session and ends every other, so that a stolen session dies.
 */
@RestController
@RequestMapping("/v1/account")
@Tag(name = "Password change", description = "Changing a signed-in Student's password with a code sent by email")
public class PasswordChangeController {

    private final PasswordChangeService changes;

    public PasswordChangeController(PasswordChangeService changes) {
        this.changes = changes;
    }

    @PostMapping("/password-change-codes")
    @PreAuthorize("hasRole('STUDENT')")
    @SecurityRequirement(name = OpenApiConfiguration.BEARER)
    @Operation(summary = "Email a change code", description = """
            Queues an email with a 6-digit code, valid for 15 minutes, to the Student's email, and voids the earlier \
            change code. A new code waits 60 seconds after the latest change code, and an Account gets at most 10 \
            codes, reset codes included, within 24 hours.""")
    @ApiResponse(responseCode = "204", description = "A code is on its way")
    @ApiResponse(responseCode = "429", description = """
            `rate-limited`, within 60 seconds of the latest change code or past 10 codes within 24 hours, with \
            `Retry-After` in seconds""")
    public ResponseEntity<Void> sendCode(@AuthenticationPrincipal AuthenticatedAccount student) {
        changes.sendCode(student.accountId());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/password")
    @PreAuthorize("hasRole('STUDENT')")
    @SecurityRequirement(name = OpenApiConfiguration.BEARER)
    @Operation(summary = "Set a new password with a change code", description = """
            Sets the Student's new password, confirms the email, keeps this session and ends every other session of \
            the Account, and queues the password-changed email. The new password is checked before the code, so a \
            refused one never spends a try; 5 wrong tries void the code. A reset code never serves here.""")
    @ApiResponse(responseCode = "204", description = "Changed; this session goes on")
    @ApiResponse(responseCode = "400", description = """
            `invalid-request` with `errors` (a `newPassword` HIBP has seen is `breached`), or `invalid-code` for a \
            code that is wrong, expired, voided or superseded, and none asked for alike""")
    public ResponseEntity<Void> change(@AuthenticationPrincipal AuthenticatedAccount student,
                                       @Valid @RequestBody PasswordChangeRequest request) {
        changes.change(student, request.code(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
