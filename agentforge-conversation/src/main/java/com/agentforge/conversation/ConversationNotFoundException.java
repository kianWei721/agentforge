package com.agentforge.conversation;

public class ConversationNotFoundException extends RuntimeException {

    public ConversationNotFoundException(String agentId, String userId, String sessionKey) {
        super(
                "Conversation session not found: agentId="
                        + agentId
                        + ", userId="
                        + userId
                        + ", sessionId="
                        + sessionKey);
    }

    public static ConversationNotFoundException byInternalId(String sessionId) {
        return new ConversationNotFoundException(
                "Conversation session not found by internal id: " + sessionId);
    }

    private ConversationNotFoundException(String message) {
        super(message);
    }
}
