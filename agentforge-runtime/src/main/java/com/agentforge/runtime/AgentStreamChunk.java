package com.agentforge.runtime;

import java.util.Map;

public record AgentStreamChunk(
        String type,
        String content,
        String source,
        boolean done,
        Map<String, Object> metadata) {

    public AgentStreamChunk {
        content = content == null ? "" : content;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static AgentStreamChunk message(String content, String source) {
        return new AgentStreamChunk("message", content, source, false, Map.of());
    }

    public static AgentStreamChunk trace(
            String type, String content, String source, Map<String, Object> metadata) {
        return new AgentStreamChunk(type, content, source, false, metadata);
    }

    public static AgentStreamChunk done(String source) {
        return new AgentStreamChunk("done", "", source, true, Map.of());
    }
}
