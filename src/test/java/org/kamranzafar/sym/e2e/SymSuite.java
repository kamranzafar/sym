package org.kamranzafar.sym.e2e;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sym's behaviour through its API, wherever it runs: in memory, on each cloud's emulators, from the packaged jar, or
 * on a real deployment. Every test uses its own authors and hashtags, so runs never see each other's data.
 */
@TestMethodOrder(MethodOrderer.DisplayName.class)
public abstract class SymSuite {

    /** A 1×1 PNG, and the start of a JPEG (JFIF) file: sym checks the bytes a type's files start with. */
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=");
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 0x10, 'J', 'F', 'I', 'F', 0};
    private static final int MAX_IMAGE_BYTES = 1024 * 1024;

    private final String run = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    protected abstract SymClient client();

    protected abstract Expectations expected();

    @Test
    void healthIsUp() {
        SymClient.Response health = client().get("/actuator/health");

        assertThat(health.status()).as(health.body()).isEqualTo(200);
        assertThat(health.json().get("status")).isEqualTo("UP");
    }

    @Test
    void postsAMessageAndReadsItBack() {
        String author = author("ada");

        SymClient.Response posted = client().post("/users/" + author + "/messages", Map.of("text", "Hello, world"));

        assertThat(posted.status()).as(posted.body()).isEqualTo(201);
        String messageId = (String) posted.json().get("messageId");
        assertThat(posted.header("Location")).isEqualTo("/users/" + author + "/messages/" + messageId);
        // twice: on the cache profile the second read comes from the cache
        for (int read = 0; read < 2; read++) {
            SymClient.Response message = client().get("/users/" + author + "/messages/" + messageId);
            assertThat(message.status()).as(message.body()).isEqualTo(200);
            assertThat(message.json()).containsEntry("author", author).containsEntry("messageId", messageId)
                    .containsEntry("text", "Hello, world").containsKey("postedAt");
        }
    }

    @Test
    void timelineIsNewestFirstAndLimited() {
        String author = author("grace");
        String first = client().postMessage(author, "one");
        String second = client().postMessage(author, "two");
        String third = client().postMessage(author, "three");

        List<Map<String, Object>> all = client().get("/users/" + author + "/messages").jsonList();
        List<Map<String, Object>> latest = client().get("/users/" + author + "/messages?limit=2").jsonList();

        assertThat(all).extracting(m -> m.get("messageId")).containsExactly(third, second, first);
        assertThat(latest).extracting(m -> m.get("text")).containsExactly("three", "two");
        assertThat(client().get("/users/" + author("nobody") + "/messages").jsonList()).isEmpty();
    }

    @Test
    void theStreamConsumerFilesMessagesUnderTheirHashtags() {
        String tag = "sym" + run;
        String older = client().postMessage(author("linus"), "Shipping it #" + tag + " #Friday");
        String newer = client().postMessage(author("margaret"), "Me too #" + tag.toUpperCase() + " and #" + tag);

        List<Map<String, Object>> tagged = await(() -> client().get("/hashtags/" + tag + "/messages").jsonList(),
                entries -> entries.size() == 2, "both messages under #" + tag);

        assertThat(tagged).extracting(m -> m.get("messageId")).containsExactly(newer, older);
        assertThat(tagged.getFirst()).containsEntry("author", author("margaret"))
                .containsEntry("text", "Me too #" + tag.toUpperCase() + " and #" + tag);
        assertThat(client().get("/hashtags/" + tag.toUpperCase() + "/messages").jsonList()).hasSize(2);
    }

    @Test
    void refusesWhatItCannotStore() {
        assertThat(postStatus("Not-A-Handle", "hi")).isEqualTo(400);
        assertThat(postStatus("a".repeat(31), "hi")).isEqualTo(400);
        assertThat(postStatus(author("alan"), "")).isEqualTo(400);
        assertThat(postStatus(author("alan"), "   ")).isEqualTo(400);
        assertThat(postStatus(author("alan"), "x".repeat(281))).isEqualTo(400);
        assertThat(client().post("/users/" + author("alan") + "/messages", Map.of()).status()).isEqualTo(400);
        assertThat(client().get("/users/" + author("alan") + "/messages?limit=0").status()).isEqualTo(400);
        assertThat(client().get("/users/" + author("alan") + "/messages?limit=101").status()).isEqualTo(400);
        assertThat(client().get("/hashtags/not-a-tag/messages").status()).isEqualTo(400);

        SymClient.Response invalid = client().post("/users/" + author("alan") + "/messages", Map.of("text", ""));
        assertThat(invalid.json()).containsEntry("detail", "a message needs some text");
    }

    @Test
    void takesExactly280CharactersCountingEmojiOnce() {
        String author = author("barbara");

        assertThat(postStatus(author, "x".repeat(280))).isEqualTo(201);
        assertThat(postStatus(author, "😀".repeat(280))).isEqualTo(201);    // 280 code points, 560 chars
        assertThat(postStatus(author, "😀".repeat(281))).isEqualTo(400);
    }

    @Test
    void anUnknownMessageIsNotFound() {
        String author = author("edsger");

        assertThat(client().get("/users/" + author + "/messages/0000000000000-000000000000").status()).isEqualTo(404);
        assertThat(client().get("/users/" + author + "/messages/not-an-id").status()).isEqualTo(404);
    }

    @Test
    void attachesAnImageAndServesItBack() {
        String author = author("ida");
        String message = "/users/" + author + "/messages/" + client().postMessage(author, "Look at this");
        // read first: on the cache profile this caches the message without an image
        assertThat(client().get(message).json()).doesNotContainKey("image");

        SymClient.Response attached = client().put(message + "/image", "image/png", PNG);

        assertThat(attached.status()).as(attached.body()).isEqualTo(200);
        assertThat(attached.json()).containsEntry("image", message + "/image");
        assertThat(client().get(message).json()).containsEntry("image", message + "/image");     // evicted, not stale
        assertThat(client().get("/users/" + author + "/messages").jsonList().getFirst())
                .containsEntry("image", message + "/image");
        SymClient.Response image = client().get(message + "/image");
        assertThat(image.status()).isEqualTo(200);
        assertThat(image.header("Content-Type")).isEqualTo("image/png");
        assertThat(image.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(image.bytes()).isEqualTo(PNG);
    }

    @Test
    void replacesAndRemovesAnImage() {
        String author = author("hedy");
        String message = "/users/" + author + "/messages/" + client().postMessage(author, "Changing my mind");
        assertThat(client().put(message + "/image", "image/png", PNG).status()).isEqualTo(200);

        assertThat(client().put(message + "/image", "image/jpeg; charset=binary", JPEG).status()).isEqualTo(200);
        SymClient.Response replaced = client().get(message + "/image");
        assertThat(replaced.header("Content-Type")).isEqualTo("image/jpeg");
        assertThat(replaced.bytes()).isEqualTo(JPEG);

        assertThat(client().delete(message + "/image").status()).isEqualTo(204);
        assertThat(client().get(message + "/image").status()).isEqualTo(404);
        assertThat(client().get(message).json()).doesNotContainKey("image");
        assertThat(client().delete(message + "/image").status()).isEqualTo(204);      // already gone: no harm
    }

    @Test
    void takesAnImageOfExactly1Mb() {
        String author = author("frances");
        String message = "/users/" + author + "/messages/" + client().postMessage(author, "Big picture");
        byte[] image = Arrays.copyOf(PNG, MAX_IMAGE_BYTES);

        assertThat(client().put(message + "/image", "image/png", image).status()).isEqualTo(200);
        assertThat(client().get(message + "/image").bytes()).isEqualTo(image);
    }

    @Test
    void refusesImagesItCannotStore() {
        String author = author("annie");
        String message = "/users/" + author + "/messages/" + client().postMessage(author, "No picture");

        assertThat(client().put(message + "/image", "image/png", new byte[0]).status()).isEqualTo(400);
        assertThat(client().put(message + "/image", "image/jpeg", PNG).status()).isEqualTo(400);
        assertThat(client().put(message + "/image", "text/html", "<p>hi</p>".getBytes()).status()).isEqualTo(415);
        assertThat(client().put(message + "/image", null, PNG).status()).isEqualTo(415);
        SymClient.Response tooLarge = client().put(message + "/image", "image/png",
                Arrays.copyOf(PNG, MAX_IMAGE_BYTES + 1));
        assertThat(tooLarge.status()).isEqualTo(413);
        assertThat(tooLarge.json()).containsEntry("detail", "an image is at most 1 MB");
        assertThat(client().get(message + "/image").status()).isEqualTo(404);
        assertThat(client().get(message).json()).doesNotContainKey("image");

        String unknown = "/users/" + author + "/messages/0000000000000-000000000000/image";
        assertThat(client().put(unknown, "image/png", PNG).status()).isEqualTo(404);
        assertThat(client().delete(unknown).status()).isEqualTo(404);
        assertThat(client().put("/users/Not-A-Handle/messages/0000000000000-000000000000/image", "image/png", PNG)
                .status()).isEqualTo(400);
    }

    private int postStatus(String author, String text) {
        return client().post("/users/" + author + "/messages", Map.of("text", text)).status();
    }

    /** A handle unique to this run, within the 30-character limit. */
    private String author(String name) {
        return name + "_" + run;
    }

    private <T> T await(Supplier<T> read, Predicate<T> condition, String what) {
        Instant deadline = Instant.now().plus(expected().patience());
        T last = read.get();
        while (!condition.test(last)) {
            assertThat(Instant.now()).as("waited %s for %s; last saw %s", expected().patience(), what, last)
                    .isBefore(deadline);
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            last = read.get();
        }
        return last;
    }
}
