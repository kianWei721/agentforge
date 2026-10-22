package com.agentforge.app.api;

import com.agentforge.rag.KnowledgeAccessScope;
import com.agentforge.rag.KnowledgeSourceType;
import com.agentforge.rag.KnowledgeVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Omitted visibility means INTERNAL: a document is never published by accident. */
public record KnowledgeDocumentCreateRequest(
        @NotBlank @Size(max = 256) String title,
        @NotNull KnowledgeSourceType sourceType,
        @Size(max = 512) String sourceRef,
        KnowledgeVisibility visibility,
        @Size(max = 64) String customerId,
        @NotBlank @Size(max = 200000) String content) {

    public KnowledgeAccessScope access() {
        return new KnowledgeAccessScope(
                visibility == null ? KnowledgeVisibility.INTERNAL : visibility, customerId);
    }
}
