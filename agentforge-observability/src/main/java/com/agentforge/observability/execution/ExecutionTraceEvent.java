package com.agentforge.observability.execution;

import java.time.Instant;
import java.util.Objects;

public record ExecutionTraceEvent(
        String id,
        String executionId,
        long sequenceNo,
        String eventType,
        String source,
        String content,
        String toolCallId,
        String toolName,
        String replyId,
        String state,
        String usageText,
        Integer inputTokens,
        Integer outputTokens,
        Integer cachedTokens,
        Long modelTimeMs,
        Instant occurredAt) {

    public ExecutionTraceEvent {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        source = source == null ? "" : source;
        content = content == null ? "" : content;
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
