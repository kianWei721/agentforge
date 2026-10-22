package com.agentforge.app.api;

import com.agentforge.rag.KnowledgeDocument;
import com.agentforge.rag.KnowledgeSourceType;
import com.agentforge.rag.KnowledgeVisibility;
import java.time.Instant;

public record KnowledgeDocumentView(
        String id,
        String title,
        KnowledgeSourceType sourceType,
        String sourceRef,
        KnowledgeVisibility visibility,
        String customerId,
        boolean enabled,
        int contentLength,
        Instant createdAt,
        Instant updatedAt) {

    public static KnowledgeDocumentView from(KnowledgeDocument document) {
        return new KnowledgeDocumentView(
                document.id(),
                document.title(),
                document.sourceType(),
                document.sourceRef(),
                document.access().visibility(),
                document.access().customerId(),
                document.enabled(),
                document.content() == null ? 0 : document.content().length(),
                document.createdAt(),
                document.updatedAt());
    }
}
