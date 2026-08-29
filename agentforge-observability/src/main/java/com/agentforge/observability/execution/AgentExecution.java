package com.agentforge.observability.execution;

import java.time.Instant;
import java.util.Objects;

public record AgentExecution(
        String id,
        String sessionId,
        String agentId,
        String userId,
        String userMessageId,
        String assistantMessageId,
        String modelProvider,
        String modelName,
        ExecutionStatus status,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        String errorType,
        String errorMessage) {

    public AgentExecution {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(userMessageId, "userMessageId must not be null");
        Objects.requireNonNull(modelProvider, "modelProvider must not be null");
        Objects.requireNonNull(modelName, "modelName must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
    }
}
