package com.devlabs.aulaflix;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads what Mailpit received, through its REST API, the way a test observes an email the API sent. Every test sends
 * to addresses of its own, so it reads only those.
 */
public final class Mailpit {

    private final MailpitContainer container;
    private final HttpClient http = HttpClient.newHttpClient();

    public Mailpit(MailpitContainer container) {
        this.container = container;
    }

    /** Every email received for the address, the newest first. */
    public List<Email> to(String address) {
        JsonNode found = get("/api/v1/search?query=" + URLEncoder.encode("to:\"" + address + "\"",
                StandardCharsets.UTF_8));
        return StreamSupport.stream(found.get("messages").spliterator(), false)
                .map(summary -> email(summary.get("ID").asString()))
                .toList();
    }

    private Email email(String id) {
        JsonNode message = get("/api/v1/message/" + id);
        JsonNode headers = get("/api/v1/message/" + id + "/headers");
        return new Email(
                headers.get("From").get(0).asString(),
                StreamSupport.stream(message.get("To").spliterator(), false)
                        .map(recipient -> recipient.get("Address").asString())
                        .toList(),
                message.get("Subject").asString(),
                message.get("Text").asString(),
                headers(headers));
    }

    private JsonNode get(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(container.apiBaseUrl() + path)).GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Mailpit answered " + response.statusCode() + " to " + path);
            }
            return JsonMapper.shared().readTree(response.body());
        } catch (IOException failure) {
            throw new IllegalStateException("Mailpit could not be read", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Reading Mailpit was interrupted", interrupted);
        }
    }

    /** An email as its recipient's client shows it: the From header, the recipients, the subject and the text. */
    public record Email(String from, List<String> to, String subject, String text,
                        Map<String, List<String>> headers) {
    }

    /** Each header's values, by the name Mailpit gives it. */
    private static Map<String, List<String>> headers(JsonNode headers) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        headers.properties().forEach(header -> values.put(header.getKey(),
                StreamSupport.stream(header.getValue().spliterator(), false).map(JsonNode::asString).toList()));
        return values;
    }
}
