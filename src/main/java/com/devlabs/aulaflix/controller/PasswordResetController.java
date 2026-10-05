package com.devlabs.aulaflix.controller;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.devlabs.aulaflix.config.OpenApiConfiguration;
import com.devlabs.aulaflix.dto.IssuedSession;
import com.devlabs.aulaflix.dto.PasswordResetCodeRequest;
import com.devlabs.aulaflix.dto.PasswordResetRequest;
import com.devlabs.aulaflix.service.PasswordResetService;

/** A forgotten password: a 6-digit code to the Student's email, then a new password with it, which signs them in. */
@RestController
@RequestMapping("/v1")
@Tag(name = "Password reset", description = "Setting a forgotten password with a code sent by email")
public class PasswordResetController {

    private final PasswordResetService resets;

    public PasswordResetController(PasswordResetService resets) {
        this.resets = resets;
    }

    @PostMapping("/password-reset-codes")
    @Operation(summary = "Email a reset code", description = """
            Queues an email with a 6-digit code, valid for 15 minutes, to a Student's email, and voids the earlier \
            reset code. It answers alike for any email, and sends nothing to an email without a Student Account, nor \
            within 60 seconds of the previous code, nor past 10 codes within 24 hours. Each client IP gets 20 a day, \
            whatever they answer.""")
    @ApiResponse(responseCode = "204", description = "A code is on its way, if the email is a Student's")
    @ApiResponse(responseCode = "400", description = "`invalid-request` with `errors`")
    @Parameter(in = ParameterIn.HEADER, name = OpenApiConfiguration.CAPTCHA_TOKEN, schema = @Schema(type = "string"),
            description = "A Turnstile token, needed past 5 requests per IP in an hour")
    public ResponseEntity<Void> sendCode(@Valid @RequestBody PasswordResetCodeRequest request) {
        resets.sendCode(request.email());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password-resets")
    @Operation(summary = "Set a new password with a reset code", description = """
            Sets the Student's new password, confirms the email, ends every session of the Account and opens a new \
            one, which ends after 7 days without use or 30 days after the reset. It also lifts a sign-in block on \
            the email, and queues the password-changed email. The new password is checked before the code, so a \
            refused one never spends a try; 5 wrong tries void the code. Each client IP gets 30 an hour, whatever \
            they answer.""")
    @ApiResponse(responseCode = "200", description = "Reset and signed in; `expiresAt` is the absolute end",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = IssuedSession.class)))
    @ApiResponse(responseCode = "400", description = """
            `invalid-request` with `errors` (a `newPassword` HIBP has seen is `breached`), or `invalid-code` for a \
            code that is wrong, expired, voided or superseded, none asked for, and an email without a Student \
            Account alike""")
    public ResponseEntity<IssuedSession> reset(@Valid @RequestBody PasswordResetRequest request) {
        return ResponseEntity.ok(resets.reset(request.email(), request.code(), request.newPassword()));
    }
}
