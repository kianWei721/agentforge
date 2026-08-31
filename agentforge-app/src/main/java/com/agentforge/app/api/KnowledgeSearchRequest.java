package com.agentforge.app.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KnowledgeSearchRequest(
        @NotBlank @Size(max = 2000) String query,
        @Min(1) @Max(20) Integer topK) {

    public int resolvedTopK() {
        return topK == null ? 5 : topK;
    }
}
