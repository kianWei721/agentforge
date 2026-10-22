package com.agentforge.runtime;

/**
 * @param source where the user message came from, stored with it ({@code api}, {@code wecom})
 */
public record AgentRuntimeRequest(
        String userId,
        String sessionId,
        String message,
        String executionId,
        String source) {

    public static final String DEFAULT_SOURCE = "api";

    public AgentRuntimeRequest {
        source = source == null || source.isBlank() ? DEFAULT_SOURCE : source;
    }

    public AgentRuntimeRequest(String userId, String sessionId, String message, String executionId) {
        this(userId, sessionId, message, executionId, DEFAULT_SOURCE);
    }

    public AgentRuntimeRequest(String userId, String sessionId, String message) {
        this(userId, sessionId, message, null, DEFAULT_SOURCE);
    }
}
