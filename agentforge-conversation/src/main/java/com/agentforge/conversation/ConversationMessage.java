package com.agentforge.conversation;

import java.time.Instant;
import java.util.Objects;

public record ConversationMessage(
        String id,
        String sessionId,
        long sequenceNo,
        ConversationRole role,
        String content,
        String source,
        Instant createdAt) {

    public ConversationMessage {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
        source = source == null ? "" : source;
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
