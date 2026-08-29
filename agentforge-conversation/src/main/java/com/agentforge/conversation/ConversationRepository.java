package com.agentforge.conversation;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository {

    ConversationSession getOrCreateSession(String agentId, String userId, String sessionKey);

    Optional<ConversationSession> findSession(String agentId, String userId, String sessionKey);

    List<ConversationSession> findSessions(String agentId, String userId);

    ConversationMessage appendMessage(
            String sessionId, ConversationRole role, String content, String source);

    List<ConversationMessage> findMessages(String sessionId);

    List<ConversationMessage> findRecentMessages(String sessionId, int limit);
}
