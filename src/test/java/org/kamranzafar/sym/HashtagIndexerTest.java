package org.kamranzafar.sym;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.domain.Limit;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class HashtagIndexerTest {

    @Test
    void findsEachHashtagOnceInLowerCase() {
        assertThat(HashtagIndexer.hashtagsIn("Go #Java and #java, #Multi_Cloud2026! #"))
                .containsExactly("java", "multi_cloud2026");
    }

    @Test
    void ignoresWhatIsNotAHashtag() {
        assertThat(HashtagIndexer.hashtagsIn("C# and email@#host, ##double, no tags")).isEmpty();
    }

    /** Streams deliver at least once: the same record handled twice leaves one entry per hashtag. */
    @Test
    void aRedeliveredRecordDoesNoHarm() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SymApplication.class)
                .web(WebApplicationType.NONE).profiles("local").run()) {
            HashtagIndexer indexer = context.getBean(HashtagIndexer.class);
            HashtagRepository hashtags = context.getBean(HashtagRepository.class);
            String record = MessageRecords.posted(
                    new Message("ada", "1792800000000-000000000001", "twice #again", Instant.parse("2026-10-24T00:00:00Z"),
                            null));

            indexer.onMessagePosted(record);
            indexer.onMessagePosted(record);

            assertThat(hashtags.findByTagOrderByMessageIdDesc("again", Limit.of(10)))
                    .singleElement()
                    .extracting(HashtagEntry::messageId, HashtagEntry::author, HashtagEntry::text)
                    .containsExactly("1792800000000-000000000001", "ada", "twice #again");
        }
    }
}
