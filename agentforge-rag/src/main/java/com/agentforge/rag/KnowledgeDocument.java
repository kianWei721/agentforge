package com.agentforge.rag;

import java.time.Instant;

public record KnowledgeDocument(
        String id,
        String title,
        KnowledgeSourceType sourceType,
        String sourceRef,
        String content,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {}
