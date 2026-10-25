package org.kamranzafar.sym;

import dev.halyx.spring.data.document.DocumentTable;
import dev.halyx.spring.data.document.PartitionKey;
import dev.halyx.spring.data.document.SortKey;

import java.time.Instant;

/**
 * A message in the {@code messages} document store, partitioned by author. Message ids sort by posting time, so an
 * author's timeline reads back newest first without an index. {@code imageType} is the content type of the message's
 * image in the {@code images} blob store, or null when it has none.
 */
@DocumentTable("messages")
public record Message(@PartitionKey String author, @SortKey String messageId, String text, Instant postedAt,
                      String imageType) {

    Message withImageType(String type) {
        return new Message(author, messageId, text, postedAt, type);
    }
}
