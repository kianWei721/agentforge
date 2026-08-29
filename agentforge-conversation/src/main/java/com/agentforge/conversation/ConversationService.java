package com.agentforge.conversation;

import java.util.List;

public class ConversationService {

    public static final String DEFAULT_USER_ID = "anonymous";
    public static final String DEFAULT_SESSION_KEY = "default";

    private static final int MAX_USER_ID_LENGTH = 128;
    private static final int MAX_SESSION_KEY_LENGTH = 128;

    private final ConversationRepository repository;

    public ConversationService(ConversationRepository repository) {
        this.repository = repository;
    }

    public ConversationSession openSession(String agentId, String userId, String sessionKey) {
        return repository.getOrCreateSession(
                required(agentId, "agentId"),
                normalizeUserId(userId),
                normalizeSessionKey(sessionKey));
    }

    public ConversationSession getSession(String agentId, String userId, String sessionKey) {
        String normalizedAgentId = required(agentId, "agentId");
        String normalizedUserId = normalizeUserId(userId);
        String normalizedSessionKey = normalizeSessionKey(sessionKey);
        return repository
                .findSession(normalizedAgentId, normalizedUserId, normalizedSessionKey)
                .orElseThrow(
                        () ->
                                new ConversationNotFoundException(
                                        normalizedAgentId,
                                        normalizedUserId,
                                        normalizedSessionKey));
    }

    public List<ConversationSession> listSessions(String agentId, String userId) {
        return repository.findSessions(required(agentId, "agentId"), normalizeUserId(userId));
    }

    public ConversationMessage appendMessage(
            String sessionId, ConversationRole role, String content, String source) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("message content must not be blank");
        }
        return repository.appendMessage(required(sessionId, "sessionId"), role, content, source);
    }

    public List<ConversationMessage> listMessages(
            String agentId, String userId, String sessionKey) {
        ConversationSession session = getSession(agentId, userId, sessionKey);
        return repository.findMessages(session.id());
    }

    public String normalizeUserId(String userId) {
        String value = normalize(userId, DEFAULT_USER_ID);
        if (value.length() > MAX_USER_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "userId must not exceed " + MAX_USER_ID_LENGTH + " characters");
        }
        return value;
    }

    public String normalizeSessionKey(String sessionKey) {
        String value = normalize(sessionKey, DEFAULT_SESSION_KEY);
        if (value.length() > MAX_SESSION_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "sessionId must not exceed " + MAX_SESSION_KEY_LENGTH + " characters");
        }
        return value;
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
