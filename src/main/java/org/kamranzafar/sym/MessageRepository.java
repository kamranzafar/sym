package org.kamranzafar.sym;

import dev.halyx.nosql.Key;
import dev.halyx.spring.data.document.DocumentRepository;
import org.springframework.data.domain.Limit;

import java.util.List;

public interface MessageRepository extends DocumentRepository<Message, Key> {

    List<Message> findByAuthorOrderByMessageIdDesc(String author, Limit limit);
}
