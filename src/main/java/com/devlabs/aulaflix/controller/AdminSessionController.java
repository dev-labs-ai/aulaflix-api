package com.devlabs.aulaflix.controller;

import java.net.URI;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.devlabs.aulaflix.config.OpenApiConfiguration;
import com.devlabs.aulaflix.dto.AdminSignInRequest;
import com.devlabs.aulaflix.dto.AuthenticatedAccount;
import com.devlabs.aulaflix.dto.IssuedSession;
import com.devlabs.aulaflix.service.SignInService;
import com.devlabs.aulaflix.service.SessionService;

@RestController
@RequestMapping("/v1/admin/sessions")
@Tag(name = "Admin sessions", description = "Admin sign-in and sign-out, through the SSH tunnel")
public class AdminSessionController {

    private static final URI CURRENT_SESSION = URI.create("/v1/admin/sessions/current");

    private final SignInService signIn;
    private final SessionService sessions;

    public AdminSessionController(SignInService signIn, SessionService sessions) {
        this.signIn = signIn;
        this.sessions = sessions;
    }

    @PostMapping
    @PreAuthorize("permitAll()")
    @Operation(summary = "Sign in as an Admin", description = """
            Opens a session that ends after 30 minutes without use, or 8 hours after sign-in. 10 failures for an \
            email within 15 minutes block that email for 15 minutes, even with the right password.""")
    @ApiResponse(responseCode = "201", description = "Signed in; `expiresAt` is the absolute end",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = IssuedSession.class)))
    @ApiResponse(responseCode = "400", description = """
            `invalid-request` with `errors`, or `invalid-credentials` for a wrong password, an unknown email and an \
            Account that is not an Admin alike""",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "`sign-in-blocked`, with `Retry-After` in seconds",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<IssuedSession> signIn(@Valid @RequestBody AdminSignInRequest request) {
        return ResponseEntity.created(CURRENT_SESSION).body(signIn.signInAsAdmin(request.email(), request.password()));
    }

    @DeleteMapping("/current")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = OpenApiConfiguration.BEARER)
    @Operation(summary = "Sign out", description = "Ends the session of this token. The Admin's other sessions go on.")
    @ApiResponse(responseCode = "204", description = "Signed out")
    public ResponseEntity<Void> signOut(@AuthenticationPrincipal AuthenticatedAccount admin) {
        sessions.signOut(admin);
        return ResponseEntity.noContent().build();
    }
}
