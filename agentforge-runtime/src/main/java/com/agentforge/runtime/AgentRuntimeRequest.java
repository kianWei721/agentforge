package com.agentforge.runtime;

public record AgentRuntimeRequest(
        String userId,
        String sessionId,
        String message,
        String executionId) {

    public AgentRuntimeRequest(String userId, String sessionId, String message) {
        this(userId, sessionId, message, null);
    }
}
