package com.agentforge.observability.execution;

public record TraceEventInput(
        String eventType,
        String source,
        String content,
        String toolCallId,
        String toolName,
        String replyId,
        String state,
        String usageText) {

    public TraceEventInput {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        source = source == null ? "" : source;
        content = content == null ? "" : content;
    }
}
