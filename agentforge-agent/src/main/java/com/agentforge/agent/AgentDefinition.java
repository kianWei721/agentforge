package com.agentforge.agent;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record AgentDefinition(
        String id,
        String name,
        String description,
        String systemPrompt,
        String modelProvider,
        String modelName,
        List<String> toolNames,
        boolean enabled,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public AgentDefinition {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
        Objects.requireNonNull(modelProvider, "modelProvider must not be null");
        Objects.requireNonNull(modelName, "modelName must not be null");
        toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }
}
