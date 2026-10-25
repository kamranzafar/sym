package org.kamranzafar.sym;

import dev.halyx.spring.messaging.HalyxListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A consumer of the {@code message-posted} stream, in its own consumer group: it files each message under every
 * hashtag in it. Any other service can read the same stream in a group of its own, at its own pace.
 */
@Component
class HashtagIndexer {

    /** A # that starts a word (at the start, or after whitespace or an opening bracket), then up to 50 word characters. */
    private static final Pattern HASHTAG = Pattern.compile("(?<![^\\s(\\[])#([A-Za-z0-9_]{1,50})");

    private final HashtagRepository hashtags;

    HashtagIndexer(HashtagRepository hashtags) {
        this.hashtags = hashtags;
    }

    /** Returning commits the record; throwing retries it with backoff. */
    @HalyxListener("hashtag-indexer")
    void onMessagePosted(String value) {
        Message message = MessageRecords.read(value);
        for (String tag : hashtagsIn(message.text())) {
            // A put by key: a redelivered record writes the same entry again, so it does no harm.
            hashtags.save(new HashtagEntry(tag, message.messageId(), message.author(), message.text(),
                    message.postedAt()));
        }
    }

    static Set<String> hashtagsIn(String text) {
        Set<String> tags = new LinkedHashSet<>();
        Matcher matcher = HASHTAG.matcher(text);
        while (matcher.find()) {
            tags.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return tags;
    }
}
