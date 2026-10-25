package org.kamranzafar.sym;

import dev.halyx.spring.data.document.DocumentTable;
import dev.halyx.spring.data.document.PartitionKey;
import dev.halyx.spring.data.document.SortKey;

import java.time.Instant;

/**
 * A message filed under one of its hashtags in the {@code hashtags} document store, written by {@link HashtagIndexer}
 * from the stream. The author and text are copied in, so a hashtag's page needs no second read.
 */
@DocumentTable("hashtags")
public record HashtagEntry(@PartitionKey String tag, @SortKey String messageId, String author, String text,
                           Instant postedAt) {
}
