package com.agentforge.observability.execution;

public record TraceEventInput(
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
        Long toolDurationMs,
        String failureCode,
        Boolean retryable,
        String governanceDecision,
        String governanceReason,
        String argumentFingerprint,
        String blockedByToolCallId,
        String blockedByFailureCode) {

    public TraceEventInput(
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
            Long modelTimeMs) {
        this(
                eventType,
                source,
                content,
                toolCallId,
                toolName,
                replyId,
                state,
                usageText,
                inputTokens,
                outputTokens,
                cachedTokens,
                modelTimeMs,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public TraceEventInput {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        source = source == null ? "" : source;
        content = content == null ? "" : content;
    }
}
