package org.kamranzafar.sym;

import dev.halyx.json.Json;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The JSON of a record on the {@code message-posted} stream, written and read in one place. It carries the whole
 * message, which is at most 280 characters, so consumers need no read back.
 */
final class MessageRecords {

    private MessageRecords() {
    }

    static String posted(Message message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("author", message.author());
        body.put("messageId", message.messageId());
        body.put("text", message.text());
        body.put("postedAt", message.postedAt().toString());
        return Json.write(body);
    }

    static Message read(String json) {
        Map<String, Object> body = Json.parseObject(json)
                .orElseThrow(() -> new IllegalArgumentException("not a message record: " + json));
        return new Message((String) body.get("author"), (String) body.get("messageId"), (String) body.get("text"),
                Instant.parse((String) body.get("postedAt")), null);
    }
}
