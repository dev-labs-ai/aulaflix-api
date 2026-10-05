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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.devlabs.aulaflix.config.OpenApiConfiguration;
import com.devlabs.aulaflix.dto.AuthenticatedAccount;
import com.devlabs.aulaflix.dto.IssuedSession;
import com.devlabs.aulaflix.dto.SignInRequest;
import com.devlabs.aulaflix.service.SessionService;
import com.devlabs.aulaflix.service.SignInService;

/** The Students' sessions, which the BFF keeps behind its cookie; the public sign-in never serves an Admin. */
@RestController
@RequestMapping("/v1/sessions")
@Tag(name = "Sessions", description = "Student sign-in and sign-out")
public class SessionController {

    private static final URI CURRENT_SESSION = URI.create("/v1/sessions/current");

    private final SignInService signIn;
    private final SessionService sessions;

    public SessionController(SignInService signIn, SessionService sessions) {
        this.signIn = signIn;
        this.sessions = sessions;
    }

    @PostMapping
    @Operation(summary = "Sign in as a Student", description = """
            Opens a session that ends after 7 days without use, or 30 days after sign-in. 10 failures for an email \
            within 15 minutes block that email for 15 minutes, even with the right password, whether or not it has \
            an Account.""")
    @ApiResponse(responseCode = "201", description = "Signed in; `expiresAt` is the absolute end",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = IssuedSession.class)))
    @ApiResponse(responseCode = "400", description = """
            `invalid-request` with `errors`, or `invalid-credentials` for a wrong password, an unknown email and an \
            Admin's email alike""")
    @ApiResponse(responseCode = "429", description = "`sign-in-blocked`, with `Retry-After` in seconds")
    public ResponseEntity<IssuedSession> signIn(@Valid @RequestBody SignInRequest request) {
        return ResponseEntity.created(CURRENT_SESSION)
                .body(signIn.signInAsStudent(request.email(), request.password()));
    }

    @DeleteMapping("/current")
    @PreAuthorize("hasRole('STUDENT')")
    @SecurityRequirement(name = OpenApiConfiguration.BEARER)
    @Operation(summary = "Sign out", description = """
            Ends the session of this token. The Student's other sessions go on. Clear the cookie whatever the \
            answer.""")
    @ApiResponse(responseCode = "204", description = "Signed out")
    public ResponseEntity<Void> signOut(@AuthenticationPrincipal AuthenticatedAccount student) {
        sessions.signOut(student);
        return ResponseEntity.noContent().build();
    }
}
