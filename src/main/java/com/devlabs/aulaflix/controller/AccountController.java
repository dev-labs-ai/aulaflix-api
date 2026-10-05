package com.devlabs.aulaflix.controller;

import java.net.URI;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.devlabs.aulaflix.config.OpenApiConfiguration;
import com.devlabs.aulaflix.dto.Account;
import com.devlabs.aulaflix.dto.AccountDocument;
import com.devlabs.aulaflix.dto.AccountLookup;
import com.devlabs.aulaflix.dto.AccountLookupRequest;
import com.devlabs.aulaflix.dto.AuthenticatedAccount;
import com.devlabs.aulaflix.dto.IssuedSession;
import com.devlabs.aulaflix.dto.SignUpRequest;
import com.devlabs.aulaflix.service.AccountService;

/**
 * The email-first way in, looking an email up then signing up, and the Account of the calling session, which is always
 * a Student's: the public flows never serve Admins.
 */
@RestController
@RequestMapping("/v1")
@Tag(name = "Accounts", description = "Signing up, and reading and editing the Student's own Account")
public class AccountController {

    private static final URI OWN_ACCOUNT = URI.create("/v1/account");

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping("/account-lookups")
    @Operation(summary = "Look up an email", description = """
            Says whether an Account has the email, so the web asks for the password or offers to sign up (ADR \
            0005). An Admin's email exists like any other.""")
    @ApiResponse(responseCode = "200", description = "Whether the email has an Account",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = AccountLookup.class)))
    @ApiResponse(responseCode = "400", description = "`invalid-request` with `errors`")
    @Parameter(in = ParameterIn.HEADER, name = OpenApiConfiguration.CAPTCHA_TOKEN, schema = @Schema(type = "string"),
            description = "A Turnstile token, needed past 10 look-ups and sign-ins per IP in 15 minutes")
    public AccountLookup lookUp(@Valid @RequestBody AccountLookupRequest request) {
        return new AccountLookup(accounts.exists(request.email()));
    }

    @PostMapping("/accounts")
    @Operation(summary = "Sign up as a Student", description = """
            Creates the Student's Account and signs them in at once: the answer is the session that sign-in would \
            open, which ends after 7 days without use, or 30 days after sign-up.""")
    @ApiResponse(responseCode = "201", description = "Signed up and in; `expiresAt` is the absolute end",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = IssuedSession.class)))
    @ApiResponse(responseCode = "400", description = "`invalid-request` with `errors`")
    @ApiResponse(responseCode = "409", description = "`email-taken`: an Account has the email, an Admin's included")
    @Parameter(in = ParameterIn.HEADER, name = OpenApiConfiguration.CAPTCHA_TOKEN, schema = @Schema(type = "string"),
            description = "A Turnstile token, needed past 3 sign-ups per IP in an hour")
    public ResponseEntity<IssuedSession> signUp(@Valid @RequestBody SignUpRequest request) {
        return ResponseEntity.created(OWN_ACCOUNT)
                .body(accounts.signUp(request.email(), request.name(), request.password()));
    }

    @GetMapping("/account")
    @PreAuthorize("hasRole('STUDENT')")
    @SecurityRequirement(name = OpenApiConfiguration.BEARER)
    @Operation(summary = "Read the Student's own Account")
    @ApiResponse(responseCode = "200", description = "The Account of the session",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = Account.class)))
    public Account account(@AuthenticationPrincipal AuthenticatedAccount student) {
        return accounts.account(student.accountId());
    }

    @PutMapping("/account")
    @PreAuthorize("hasRole('STUDENT')")
    @SecurityRequirement(name = OpenApiConfiguration.BEARER)
    @Operation(summary = "Edit the Student's own name", description = """
            Replaces the Account's editable document, which is only the name: the email never changes.""")
    @ApiResponse(responseCode = "200", description = "The Account as it now is",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = Account.class)))
    @ApiResponse(responseCode = "400", description = "`invalid-request` with `errors`")
    public Account rename(@AuthenticationPrincipal AuthenticatedAccount student,
                          @Valid @RequestBody AccountDocument document) {
        return accounts.rename(student.accountId(), document.name());
    }
}
