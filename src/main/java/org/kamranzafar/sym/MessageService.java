package org.kamranzafar.sym;

import dev.halyx.error.NotFoundException;
import dev.halyx.nosql.Key;
import dev.halyx.storage.Blob;
import dev.halyx.storage.BlobContent;
import dev.halyx.storage.BlobKey;
import dev.halyx.storage.BlobStore;
import dev.halyx.storage.PutOptions;
import dev.halyx.stream.StreamProducer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Posting and reading messages. A posted message is stored, then streamed to {@code message-posted}. A message may
 * have one image, kept in the {@code images} blob store under {@code images/<author>/<messageId>}.
 */
@Service
public class MessageService {

    static final int MAX_LENGTH = 280;
    static final int MAX_PAGE = 100;
    static final int MAX_IMAGE_BYTES = 1024 * 1024;

    private static final Pattern HANDLE = Pattern.compile("[a-z0-9_]{1,30}");
    private static final Pattern MESSAGE_ID = Pattern.compile("\\d{13}-[0-9a-f]{12}");
    private static final Pattern TAG = Pattern.compile("[a-z0-9_]{1,50}");

    /** The image types sym takes, each with a check of the bytes its files start with. */
    private static final Map<String, Predicate<byte[]>> IMAGE_TYPES = Map.of(
            "image/png", bytes -> startsWith(bytes, 0, new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'}),
            "image/jpeg", bytes -> startsWith(bytes, 0, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff}),
            "image/gif", bytes -> startsWith(bytes, 0, ascii("GIF87a")) || startsWith(bytes, 0, ascii("GIF89a")),
            "image/webp", bytes -> startsWith(bytes, 0, ascii("RIFF")) && startsWith(bytes, 8, ascii("WEBP")));

    /** A message's image, as it was stored. */
    public record Image(String contentType, byte[] bytes) {
    }

    private final MessageRepository messages;
    private final HashtagRepository hashtags;
    private final StreamProducer posted;
    private final BlobStore images;

    MessageService(MessageRepository messages, HashtagRepository hashtags,
                   @Qualifier("message-posted") StreamProducer posted, @Qualifier("images") BlobStore images) {
        this.messages = messages;
        this.hashtags = hashtags;
        this.posted = posted;
        this.images = images;
    }

    public Message post(String author, String text) {
        requireHandle(author);
        if (text == null || text.isBlank()) {
            throw new InvalidMessageException("a message needs some text");
        }
        if (text.codePointCount(0, text.length()) > MAX_LENGTH) {
            throw new InvalidMessageException("a message is at most " + MAX_LENGTH + " characters");
        }
        Message message = messages.save(new Message(author, newMessageId(), text, Instant.now(), null));
        // Stored first, then streamed, keyed by author so each author's messages stay in order. If the stream is
        // down, the message stays stored but unstreamed and the caller gets a 503: sym has no outbox, so a retry
        // posts the message again under a new id.
        posted.send(author, MessageRecords.posted(message));
        return message;
    }

    /**
     * One message; cached on the {@code cache} profile. A message's text never changes, but its image can, so
     * attaching and removing one evict it.
     */
    @Cacheable(cacheNames = "messages", key = "#author + '/' + #messageId", unless = "#result == null")
    public Optional<Message> find(String author, String messageId) {
        return stored(author, messageId);
    }

    /**
     * Stores an image for a message, replacing any it had, and returns the message, or empty when there is no such
     * message. The image is stored before the message points at it, so a failure leaves at most an image nothing
     * refers to, which a retry overwrites.
     */
    @CacheEvict(cacheNames = "messages", key = "#author + '/' + #messageId")
    public Optional<Message> attachImage(String author, String messageId, String contentType, byte[] image) {
        requireHandle(author);
        String type = imageType(contentType, image);
        return stored(author, messageId).map(message -> {
            images.put(imageKey(author, messageId), BlobContent.ofBytes(image),
                    PutOptions.builder().contentType(type).build());
            return messages.save(message.withImageType(type));
        });
    }

    /** A message's image, or empty when the message has none. */
    public Optional<Image> image(String author, String messageId) {
        requireHandle(author);
        if (!MESSAGE_ID.matcher(messageId).matches()) {
            return Optional.empty();
        }
        // Images are at most 1 MB, so they're read whole: the blob is closed before the response is written.
        try (Blob blob = images.get(imageKey(author, messageId))) {
            return Optional.of(new Image(blob.metadata().contentType().orElse("application/octet-stream"),
                    blob.readAllBytes()));
        } catch (NotFoundException e) {
            return Optional.empty();
        }
    }

    /**
     * Removes a message's image, if it has one, and returns the message, or empty when there is no such message. The
     * message stops pointing at the image first, so a failed delete leaves an image nothing refers to, never a message
     * whose image is gone; deleting a missing image isn't an error, so a retry finishes the job.
     */
    @CacheEvict(cacheNames = "messages", key = "#author + '/' + #messageId")
    public Optional<Message> removeImage(String author, String messageId) {
        return stored(author, messageId).map(message -> {
            Message withoutImage = message.imageType() == null ? message : messages.save(message.withImageType(null));
            images.delete(imageKey(author, messageId));
            return withoutImage;
        });
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

    private Optional<Message> stored(String author, String messageId) {
        requireHandle(author);
        if (!MESSAGE_ID.matcher(messageId).matches()) {
            return Optional.empty();
        }
        return messages.findById(Key.of(author, messageId));
    }

    private static BlobKey imageKey(String author, String messageId) {
        return BlobKey.of("images/" + author + "/" + messageId);
    }

    /** The image's content type, without parameters, once the image is known to be one sym takes. */
    private static String imageType(String contentType, byte[] image) {
        if (image.length == 0) {
            throw new InvalidImageException(InvalidImageException.Reason.EMPTY, "an image needs some bytes");
        }
        if (image.length > MAX_IMAGE_BYTES) {
            throw new InvalidImageException(InvalidImageException.Reason.TOO_LARGE, "an image is at most 1 MB");
        }
        String type = contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        Predicate<byte[]> check = IMAGE_TYPES.get(type);
        if (check == null) {
            throw new InvalidImageException(InvalidImageException.Reason.UNSUPPORTED_TYPE,
                    "an image is a PNG, JPEG, GIF or WebP");
        }
        if (!check.test(image)) {
            throw new InvalidImageException(InvalidImageException.Reason.NOT_THAT_TYPE, "the image isn't a " + type);
        }
        return type;
    }

    private static boolean startsWith(byte[] bytes, int offset, byte[] prefix) {
        return bytes.length >= offset + prefix.length
                && Arrays.equals(bytes, offset, offset + prefix.length, prefix, 0, prefix.length);
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
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
