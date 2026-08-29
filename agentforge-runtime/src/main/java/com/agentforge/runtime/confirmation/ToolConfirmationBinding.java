package com.agentforge.runtime.confirmation;

import java.util.Objects;

public record ToolConfirmationBinding(
        String agentId,
        String userId,
        String sessionId,
        String toolName,
        String argumentFingerprint) {

    public ToolConfirmationBinding {
        agentId = required(agentId, "agentId");
        userId = required(userId, "userId");
        sessionId = required(sessionId, "sessionId");
        toolName = required(toolName, "toolName");
        argumentFingerprint = required(argumentFingerprint, "argumentFingerprint");
    }

    private static String required(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
