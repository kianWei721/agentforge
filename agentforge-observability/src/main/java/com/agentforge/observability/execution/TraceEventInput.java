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
        String blockedByFailureCode,
        String outboundProtocol,
        String downstreamSystem,
        Integer outboundAttempts,
        Integer httpStatus,
        Integer businessCode,
        Long downstreamDurationMs,
        String outboundOutcome,
        String toolRiskLevel,
        Boolean toolReadOnly,
        String policyDecision,
        String policyReason) {

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
                null,
                null,
                null,
                null,
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
