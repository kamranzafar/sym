package org.kamranzafar.sym;

import dev.halyx.nosql.Key;
import dev.halyx.spring.data.document.DocumentRepository;
import org.springframework.data.domain.Limit;

import java.util.List;

public interface HashtagRepository extends DocumentRepository<HashtagEntry, Key> {

    List<HashtagEntry> findByTagOrderByMessageIdDesc(String tag, Limit limit);
}
