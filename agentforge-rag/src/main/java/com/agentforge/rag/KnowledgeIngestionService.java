package com.agentforge.rag;

import com.agentforge.core.security.AccessDeniedException;
import com.agentforge.core.security.AccessPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Document management. Writes are staff-only; reads apply the same {@link KnowledgeAccessPolicy}
 * as retrieval, so the document API cannot be used to read around the search filter.
 */
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
            KnowledgeAccessScope access,
            String content,
            AccessPrincipal actor) {
        AccessDeniedException.requireStaff(actor, "creating knowledge documents");
        requireText(title, "title");
        if (sourceType == null) {
            throw new IllegalArgumentException("sourceType is required");
        }
        if (access == null) {
            throw new IllegalArgumentException("access is required");
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
                        access,
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

    public List<KnowledgeDocument> list(AccessPrincipal principal) {
        KnowledgeAccessPolicy.requirePrincipal(principal);
        return repository.findAll().stream()
                .filter(document -> isReadable(document, principal))
                .toList();
    }

    /** Aggregate for the unauthenticated health endpoint; exposes no titles or content. */
    public int count() {
        return repository.findAll().size();
    }

    /** Unreadable documents are reported exactly like missing ones, so ids cannot be probed. */
    public KnowledgeDocument get(String id, AccessPrincipal principal) {
        KnowledgeAccessPolicy.requirePrincipal(principal);
        return repository
                .findById(id)
                .filter(document -> isReadable(document, principal))
                .orElseThrow(() -> new IllegalArgumentException("knowledge document not found: " + id));
    }

    public void delete(String id, AccessPrincipal actor) {
        AccessDeniedException.requireStaff(actor, "deleting knowledge documents");
        repository.deleteById(id);
    }

    private static boolean isReadable(KnowledgeDocument document, AccessPrincipal principal) {
        if (principal.isStaff()) {
            return true;
        }
        return document.enabled() && KnowledgeAccessPolicy.permits(principal, document.access());
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
