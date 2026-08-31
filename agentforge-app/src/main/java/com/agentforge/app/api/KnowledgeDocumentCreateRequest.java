package com.agentforge.app.api;

import com.agentforge.rag.KnowledgeSourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record KnowledgeDocumentCreateRequest(
        @NotBlank @Size(max = 256) String title,
        @NotNull KnowledgeSourceType sourceType,
        @Size(max = 512) String sourceRef,
        @NotBlank @Size(max = 200000) String content) {}
