package org.kamranzafar.sym;

import dev.halyx.nosql.Key;
import dev.halyx.stream.StreamProducer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/** Posting and reading messages. A posted message is stored, then streamed to {@code message-posted}. */
@Service
public class MessageService {

    static final int MAX_LENGTH = 280;
    static final int MAX_PAGE = 100;

    private static final Pattern HANDLE = Pattern.compile("[a-z0-9_]{1,30}");
    private static final Pattern MESSAGE_ID = Pattern.compile("\\d{13}-[0-9a-f]{12}");
    private static final Pattern TAG = Pattern.compile("[a-z0-9_]{1,50}");

    private final MessageRepository messages;
    private final HashtagRepository hashtags;
    private final StreamProducer posted;

    MessageService(MessageRepository messages, HashtagRepository hashtags,
                   @Qualifier("message-posted") StreamProducer posted) {
        this.messages = messages;
        this.hashtags = hashtags;
        this.posted = posted;
    }

    public Message post(String author, String text) {
        requireHandle(author);
        if (text == null || text.isBlank()) {
            throw new InvalidMessageException("a message needs some text");
        }
        if (text.codePointCount(0, text.length()) > MAX_LENGTH) {
            throw new InvalidMessageException("a message is at most " + MAX_LENGTH + " characters");
        }
        Message message = messages.save(new Message(author, newMessageId(), text, Instant.now()));
        // Stored first, then streamed, keyed by author so each author's messages stay in order. If the stream is
        // down, the message stays stored but unstreamed and the caller gets a 503: sym has no outbox, so a retry
        // posts the message again under a new id.
        posted.send(author, MessageRecords.posted(message));
        return message;
    }

    /** One message; cached on the {@code cache} profile. Messages never change, so nothing evicts them. */
    @Cacheable(cacheNames = "messages", key = "#author + '/' + #messageId", unless = "#result == null")
    public Optional<Message> find(String author, String messageId) {
        requireHandle(author);
        if (!MESSAGE_ID.matcher(messageId).matches()) {
            return Optional.empty();
        }
        return messages.findById(Key.of(author, messageId));
    }

    /** An author's messages, newest first. */
    public List<Message> timeline(String author, int limit) {
        requireHandle(author);
        return messages.findByAuthorOrderByMessageIdDesc(author, page(limit));
    }

    /** The messages filed under a hashtag, newest first, as the stream consumer has indexed them so far. */
    public List<HashtagEntry> tagged(String tag, int limit) {
        String normalized = tag.toLowerCase(Locale.ROOT);
        if (!TAG.matcher(normalized).matches()) {
            throw new InvalidMessageException("a hashtag is 1 to 50 letters, digits or underscores");
        }
        return hashtags.findByTagOrderByMessageIdDesc(normalized, page(limit));
    }

    private static void requireHandle(String author) {
        if (author == null || !HANDLE.matcher(author).matches()) {
            throw new InvalidMessageException("a handle is 1 to 30 lowercase letters, digits or underscores");
        }
    }

    private static Limit page(int limit) {
        if (limit < 1 || limit > MAX_PAGE) {
            throw new InvalidMessageException("limit is 1 to " + MAX_PAGE);
        }
        return Limit.of(limit);
    }

    /** Fixed-width milliseconds, so ids sort by time as strings, and a random suffix, so two in one millisecond differ. */
    private static String newMessageId() {
        return "%013d-%012x".formatted(System.currentTimeMillis(), ThreadLocalRandom.current().nextLong(1L << 48));
    }
}
