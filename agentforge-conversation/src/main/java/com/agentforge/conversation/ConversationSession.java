package com.agentforge.conversation;

import java.time.Instant;
import java.util.Objects;

public record ConversationSession(
        String id,
        String agentId,
        String userId,
        String sessionKey,
        String status,
        Instant createdAt,
        Instant updatedAt,
        Instant lastMessageAt) {

    public ConversationSession {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(sessionKey, "sessionKey must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }
}
