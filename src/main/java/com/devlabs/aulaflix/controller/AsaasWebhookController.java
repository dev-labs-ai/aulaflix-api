package com.devlabs.aulaflix.controller;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.devlabs.aulaflix.config.OpenApiConfiguration;
import com.devlabs.aulaflix.service.AsaasWebhookInbox;

/**
 * Asaas's webhook, the API's only endpoint reached from the internet: through the edge, from Asaas's IPs alone,
 * without the BFF's key. The token is checked before the request gets here.
 */
@RestController
@RequestMapping("/v1/webhooks/asaas")
@Tag(name = "Webhooks", description = "Asaas's payment events")
public class AsaasWebhookController {

    private final AsaasWebhookInbox inbox;

    public AsaasWebhookController(AsaasWebhookInbox inbox) {
        this.inbox = inbox;
    }

    /** The body is read as it arrived, never bound: whatever it holds is stored, and Asaas gets its 200. */
    @PostMapping
    @Operation(summary = "Receive an Asaas event", description = """
            Stores the event as it arrived and answers at once; a worker then re-reads the charge it names from \
            Asaas, and only that re-read changes anything. Anything with the right token gets a 200, so that Asaas \
            stops delivering it: a repeated event id, an event the API does not handle, even a body that is no \
            event.""")
    @SecurityRequirement(name = OpenApiConfiguration.WEBHOOK_TOKEN)
    @RequestBody(required = true, description = "An Asaas event, at most 256 KB",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "object", example = """
                            {"id": "evt_05b708f961d739ea7eba7e4db318f621&368604920", "event": "PAYMENT_CONFIRMED",
                             "payment": {"id": "pay_080225913252", "externalReference": "K7M2Q9XA"}}""")))
    @ApiResponse(responseCode = "200", description = "Stored, or received before; the body is empty")
    @ApiResponse(responseCode = "403", description = "`invalid-webhook-token`: the token is missing or wrong")
    @ApiResponse(responseCode = "413", description = "`content-too-large`: the body is over 256 KB")
    public ResponseEntity<Void> receive(HttpServletRequest request) throws IOException {
        inbox.receive(request.getInputStream());
        return ResponseEntity.ok().build();
    }
}
