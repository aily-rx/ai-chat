package com.ailyrx.aichat.kb;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class InMemoryDocumentStore implements DocumentStore {

    private final Map<String, Entry> documents = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public DocumentRecord save(DocumentRecord document) {
        documents.put(document.id(), new Entry(document, sequence.incrementAndGet()));
        return document;
    }

    @Override
    public Optional<DocumentRecord> findById(String id) {
        Entry entry = documents.get(id);
        return entry == null ? Optional.empty() : Optional.of(entry.document());
    }

    @Override
    public List<DocumentRecord> findAll() {
        Comparator<Entry> newestFirst = Comparator
                .comparing((Entry entry) -> entry.document().createdAt())
                .thenComparingLong(Entry::sequence)
                .reversed();
        return documents.values().stream()
                .sorted(newestFirst)
                .map(Entry::document)
                .toList();
    }

    /**
     * 仅测试使用：清空内存数据，避免测试用例相互污染。
     */
    public void clear() {
        documents.clear();
        sequence.set(0);
    }

    private record Entry(DocumentRecord document, long sequence) {
    }
}
