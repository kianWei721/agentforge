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
}
