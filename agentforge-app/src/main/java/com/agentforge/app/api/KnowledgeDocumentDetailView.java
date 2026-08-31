package com.agentforge.app.api;

import com.agentforge.rag.KnowledgeDocument;
import com.agentforge.rag.KnowledgeSourceType;
import java.time.Instant;

public record KnowledgeDocumentDetailView(
        String id,
        String title,
        KnowledgeSourceType sourceType,
        String sourceRef,
        String content,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {

    public static KnowledgeDocumentDetailView from(KnowledgeDocument document) {
        return new KnowledgeDocumentDetailView(
                document.id(),
                document.title(),
                document.sourceType(),
                document.sourceRef(),
                document.content(),
                document.enabled(),
                document.createdAt(),
                document.updatedAt());
    }
}
