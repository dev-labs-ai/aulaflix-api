package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AsaasWebhooks.newEventId;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.devlabs.aulaflix.Asaas;
import com.devlabs.aulaflix.AsaasWebhooks;
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.StoredWebhookEvents;
import com.devlabs.aulaflix.TestcontainersConfiguration;

/**
 * Asaas's webhook over real HTTP, through Tomcat, as the edge forwards it: the body is stored byte for byte as it
 * arrived, and one over 256 KB, the edge's own limit, is refused. This needs a server on a port, so it boots an
 * application context of its own, with the same settings as every other integration test's.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"aulaflix.bff.key=" + BffApi.KEY, "aulaflix.scheduling.enabled=false"})
@Import(TestcontainersConfiguration.class)
class AsaasWebhookIT {

    private static final int MAX_BODY_BYTES = 256 * 1024;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void storesTheBodyExactlyAsItArrived() {
        String eventId = newEventId();
        byte[] body = ("{\r\n  \"id\" : \"%s\",\t\"event\":\"PAYMENT_CREATED\" ,\n"
                + "  \"payment\": {\"description\": \"Inscrição em \\u00e7 ✓ 🎓\", \"value\": 447.30}}\n")
                .formatted(eventId).getBytes(StandardCharsets.UTF_8);

        http().post().uri("/v1/webhooks/asaas")
                .header(AsaasWebhooks.TOKEN_HEADER, Asaas.WEBHOOK_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody().isEmpty();

        assertThat(new StoredWebhookEvents(jdbc).bodyOf(eventId)).isEqualTo(body);
    }

    @Test
    void takesABodyOfExactly256Kilobytes() {
        String eventId = newEventId();
        byte[] body = eventOfLength(eventId, MAX_BODY_BYTES);

        http().post().uri("/v1/webhooks/asaas")
                .header(AsaasWebhooks.TOKEN_HEADER, Asaas.WEBHOOK_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isOk();

        assertThat(new StoredWebhookEvents(jdbc).bodyOf(eventId)).hasSize(MAX_BODY_BYTES);
    }

    @Test
    void refusesABodyOver256KilobytesAndStoresNothing() {
        String eventId = newEventId();

        http().post().uri("/v1/webhooks/asaas")
                .header(AsaasWebhooks.TOKEN_HEADER, Asaas.WEBHOOK_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body(eventOfLength(eventId, MAX_BODY_BYTES + 1))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONTENT_TOO_LARGE)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.type").isEqualTo("https://aulaflix.com.br/problems/content-too-large");

        assertThat(new StoredWebhookEvents(jdbc).statesOf(eventId)).isEmpty();
    }

    private RestTestClient http() {
        return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    /** An event Asaas could send, padded to exactly this many bytes. */
    private static byte[] eventOfLength(String eventId, int length) {
        String start = "{\"id\": \"%s\", \"event\": \"PAYMENT_CREATED\", \"padding\": \"".formatted(eventId);
        String end = "\"}";
        return (start + "x".repeat(length - start.length() - end.length()) + end).getBytes(StandardCharsets.UTF_8);
    }
}
