package com.agentforge.rag;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class KnowledgeIngestionService {

    private final KnowledgeRepository repository;
    private final KnowledgeChunker chunker;

    public KnowledgeIngestionService(
            KnowledgeRepository repository, KnowledgeChunker chunker) {
        this.repository = repository;
        this.chunker = chunker;
    }

    public KnowledgeDocument create(
            String title,
            KnowledgeSourceType sourceType,
            String sourceRef,
            String content) {
        requireText(title, "title");
        if (sourceType == null) {
            throw new IllegalArgumentException("sourceType is required");
        }
        requireText(content, "content");

        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        KnowledgeDocument document =
                new KnowledgeDocument(
                        id,
                        title.trim(),
                        sourceType,
                        sourceRef == null ? "" : sourceRef.trim(),
                        content.trim(),
                        true,
                        now,
                        now);
        List<KnowledgeChunk> chunks = chunker.chunk(id, document.content());
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("content produced no searchable chunks");
        }
        return repository.save(document, chunks);
    }

    public List<KnowledgeDocument> list() {
        return repository.findAll();
    }

    public KnowledgeDocument get(String id) {
        return repository
                .findById(id)
                .orElseThrow(() -> new IllegalArgumentException("knowledge document not found: " + id));
    }

    public void delete(String id) {
        repository.deleteById(id);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
