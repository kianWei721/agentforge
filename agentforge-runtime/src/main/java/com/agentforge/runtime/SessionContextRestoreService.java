package com.agentforge.runtime;

import com.agentforge.conversation.ConversationMessage;
import com.agentforge.conversation.ConversationRole;
import com.agentforge.conversation.ConversationService;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import java.util.List;
import java.util.Optional;

public class SessionContextRestoreService {

    private static final String AGENT_STATE_KEY = "agent_state";

    private final ConversationService conversationService;
    private final AgentStateStore stateStore;
    private final int restoreMaxMessages;

    public SessionContextRestoreService(
            ConversationService conversationService,
            AgentStateStore stateStore,
            int restoreMaxMessages) {
        this.conversationService = conversationService;
        this.stateStore = stateStore;
        this.restoreMaxMessages = restoreMaxMessages;
    }

    public ContextRestoreResult ensureContext(String userId, String sessionId) {
        Optional<AgentState> cached =
                stateStore.get(userId, sessionId, AGENT_STATE_KEY, AgentState.class);
        if (cached.isPresent()) {
            return new ContextRestoreResult(
                    ContextRestoreSource.CACHE,
                    cached.get().getContext().size());
        }

        List<ConversationMessage> history =
                conversationService.listRecentMessagesBySessionId(
                        sessionId, restoreMaxMessages);
        List<Msg> context = history.stream().map(SessionContextRestoreService::toAgentMessage).toList();

        AgentState.Builder builder =
                AgentState.builder()
                        .sessionId(sessionId)
                        .context(context);
        if (userId != null && !userId.isBlank()) {
            builder.userId(userId);
        }

        stateStore.save(userId, sessionId, AGENT_STATE_KEY, builder.build());

        return new ContextRestoreResult(
                history.isEmpty()
                        ? ContextRestoreSource.EMPTY
                        : ContextRestoreSource.MYSQL_TRANSCRIPT,
                history.size());
    }

    private static Msg toAgentMessage(ConversationMessage message) {
        if (message.role() == ConversationRole.USER) {
            return UserMessage.builder()
                    .id(message.id())
                    .name("user")
                    .textContent(message.content())
                    .build();
        }
        return AssistantMessage.builder()
                .id(message.id())
                .name("assistant")
                .textContent(message.content())
                .build();
    }

    public enum ContextRestoreSource {
        CACHE,
        MYSQL_TRANSCRIPT,
        EMPTY
    }

    public record ContextRestoreResult(ContextRestoreSource source, int restoredMessages) {}
}
