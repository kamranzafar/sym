package org.kamranzafar.sym.e2e;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Instant;
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
