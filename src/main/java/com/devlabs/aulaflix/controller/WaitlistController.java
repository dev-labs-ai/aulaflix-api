package com.devlabs.aulaflix.controller;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.devlabs.aulaflix.config.OpenApiConfiguration;
import com.devlabs.aulaflix.dto.AuthenticatedAccount;
import com.devlabs.aulaflix.dto.WaitlistEntryRequest;
import com.devlabs.aulaflix.service.WaitlistService;

/**
 * A Coming soon Course's Waitlist: a Visitor joins by email, a Student in one click with the Account's email, under
 * the singleton {@code /v1/account}, so there is no entry id to authorize. Only Students leave; Visitors unsubscribe.
 * Admins never join. Course ids of any shape answer like unknown ones.
 */
@RestController
@RequestMapping("/v1")
@Tag(name = "Waitlist", description = "Waiting for a Coming soon Course to go On sale")
public class WaitlistController {

    private static final String WAITLIST_CLOSED = """
            `waitlist-closed`: no Course has the id, or it is a Draft, or it is On sale""";

    private final WaitlistService waitlists;

    public WaitlistController(WaitlistService waitlists) {
        this.waitlists = waitlists;
    }

    @PostMapping("/waitlist-entries")
    @PreAuthorize("!hasRole('ADMIN')")
    @Operation(summary = "Join a Waitlist by email", description = """
            Puts the email on the Coming soon Course's Waitlist, to hear when it goes On sale. Single opt-in: no \
            email is sent on joining. Joining again changes nothing, and the answer is alike whoever is listed or has \
            an Account. Each client IP gets 10 a day, whatever they answer.""")
    @ApiResponse(responseCode = "204", description = "The email is on the Waitlist")
    @ApiResponse(responseCode = "400", description = "`invalid-request` with `errors`")
    @ApiResponse(responseCode = "403", description = "`forbidden`: an Admin's session")
    @ApiResponse(responseCode = "409", description = WAITLIST_CLOSED)
    @Parameter(in = ParameterIn.HEADER, name = OpenApiConfiguration.CAPTCHA_TOKEN, schema = @Schema(type = "string"),
            description = "A Turnstile token, needed past 3 requests per IP in an hour")
    public ResponseEntity<Void> join(@Valid @RequestBody WaitlistEntryRequest request) {
        waitlists.joinAsVisitor(request.courseId(), request.email());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/account/waitlists/{courseId}")
    @PreAuthorize("hasRole('STUDENT')")
    @SecurityRequirement(name = OpenApiConfiguration.BEARER)
    @Operation(summary = "Tell whether the Student is on a Course's Waitlist", description = """
            The Student is on it when an entry holds the Account's email, made as a Visitor before signing up \
            too.""")
    @ApiResponse(responseCode = "204", description = "The Student is on the Waitlist")
    @ApiResponse(responseCode = "404", description = """
            `not-on-waitlist`: no entry of the Course's Waitlist holds the Account's email, or no Course has the id""")
    public ResponseEntity<Void> check(@AuthenticationPrincipal AuthenticatedAccount student,
                                      @PathVariable String courseId) {
        waitlists.requireOn(student.accountId(), courseId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/account/waitlists/{courseId}")
    @PreAuthorize("hasRole('STUDENT')")
    @SecurityRequirement(name = OpenApiConfiguration.BEARER)
    @Operation(summary = "Join a Waitlist with the Account's email", description = """
            Idempotent: joining a Waitlist the Account's email is on already changes nothing. No email is sent.""")
    @ApiResponse(responseCode = "204", description = "The Student is on the Waitlist")
    @ApiResponse(responseCode = "409", description = WAITLIST_CLOSED)
    public ResponseEntity<Void> enter(@AuthenticationPrincipal AuthenticatedAccount student,
                                      @PathVariable String courseId) {
        waitlists.joinAsStudent(student.accountId(), courseId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/account/waitlists/{courseId}")
    @PreAuthorize("hasRole('STUDENT')")
    @SecurityRequirement(name = OpenApiConfiguration.BEARER)
    @Operation(summary = "Leave a Waitlist", description = """
            Takes the Account's email off the Course's Waitlist. Idempotent: leaving a Waitlist the Student is not \
            on, or of a Course that does not exist, changes nothing.""")
    @ApiResponse(responseCode = "204", description = "The Student is not on the Waitlist")
    public ResponseEntity<Void> leave(@AuthenticationPrincipal AuthenticatedAccount student,
                                      @PathVariable String courseId) {
        waitlists.leave(student.accountId(), courseId);
        return ResponseEntity.noContent().build();
    }
}
