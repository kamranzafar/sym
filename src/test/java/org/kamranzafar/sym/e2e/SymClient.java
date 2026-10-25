package org.kamranzafar.sym.e2e;

import dev.halyx.json.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Sym's HTTP API as the end-to-end tests use it: plain HTTP and Halyx's JSON reader, so the same tests run in
 * process, against the packaged jar, and against a deployment on a real cloud.
 */
public final class SymClient {

    /** A response: its status and body, the body parsed when it is JSON. */
    public record Response(int status, String body, Map<String, List<String>> headers) {

        @SuppressWarnings("unchecked")
        public Map<String, Object> json() {
            return (Map<String, Object>) Json.parse(body);
        }

        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> jsonList() {
            return (List<Map<String, Object>>) Json.parse(body);
        }

        public String header(String name) {
            return headers.getOrDefault(name.toLowerCase(), List.of()).stream().findFirst().orElse(null);
        }
    }

    private final URI base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public SymClient(URI base) {
        this.base = base;
    }

    public Response get(String path) {
        return send(HttpRequest.newBuilder(base.resolve(path)).GET());
    }

    public Response post(String path, Object json) {
        return send(HttpRequest.newBuilder(base.resolve(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(json))));
    }

    /** Posts a message and returns its id, failing the test if the post isn't a 201. */
    public String postMessage(String author, String text) {
        Response response = post("/users/" + author + "/messages", Map.of("text", text));
        if (response.status() != 201) {
            throw new AssertionError("POST as " + author + " returned " + response.status() + ": " + response.body());
        }
        return (String) response.json().get("messageId");
    }

    private Response send(HttpRequest.Builder request) {
        try {
            HttpResponse<String> response = http.send(request.timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body(), response.headers().map());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
