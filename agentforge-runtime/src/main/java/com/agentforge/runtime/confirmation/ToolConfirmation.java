package com.agentforge.runtime.confirmation;

import java.time.Instant;
import java.util.Objects;

public record ToolConfirmation(
        String id,
        String activeKey,
        String requestedExecutionId,
        String requestedToolCallId,
        String consumedExecutionId,
        String consumedToolCallId,
        String agentId,
        String userId,
        String sessionId,
        String toolName,
        String argumentFingerprint,
        String summary,
        ToolConfirmationStatus status,
        Instant expiresAt,
        Instant approvedAt,
        Instant rejectedAt,
        Instant consumedAt,
        Instant createdAt,
        Instant updatedAt) {

    public ToolConfirmation {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(requestedExecutionId, "requestedExecutionId must not be null");
        Objects.requireNonNull(requestedToolCallId, "requestedToolCallId must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(argumentFingerprint, "argumentFingerprint must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        summary = summary == null || summary.isBlank() ? toolName : summary.trim();
    }
}
