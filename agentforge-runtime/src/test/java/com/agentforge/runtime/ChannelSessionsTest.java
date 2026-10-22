package com.agentforge.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.agent.AgentDefinition;
import com.agentforge.agent.AgentDefinitionRepository;
import com.agentforge.conversation.ConversationMessage;
import com.agentforge.conversation.ConversationRepository;
import com.agentforge.conversation.ConversationRole;
import com.agentforge.conversation.ConversationService;
import com.agentforge.conversation.ConversationSession;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChannelSessionsTest {

    private static final String CHANNEL_KEY = "wecom:3f1c";

    @Test
    void onlyTheChannelWritesItsSessionsWhateverTheCase() {
        ChannelSessions.requireWriter(CHANNEL_KEY, ChannelSessions.WECOM_SOURCE);
        ChannelSessions.requireWriter("my-session", AgentRuntimeRequest.DEFAULT_SOURCE);

        assertThrows(
                IllegalArgumentException.class,
                () -> ChannelSessions.requireWriter(CHANNEL_KEY, AgentRuntimeRequest.DEFAULT_SOURCE));
        assertThrows(
                IllegalArgumentException.class,
                () -> ChannelSessions.requireWriter(" WeCom:3f1c", AgentRuntimeRequest.DEFAULT_SOURCE));
    }

    @Test
    void theApiCannotReachAChannelSessionThroughADifferentlyWrittenKey() {
        // MySQL's _ai_ci collation resolves "wécom:3f1c" to the stored "wecom:3f1c" row.
        RecordingConversations conversations = new RecordingConversations(CHANNEL_KEY);
        AgentConversationRuntimeService service =
                new AgentConversationRuntimeService(
                        new EnabledAgent(), new ConversationService(conversations), null, null, null);

        AgentRuntimeRequest request = new AgentRuntimeRequest("dev-staff", "wécom:3f1c", "hello");

        assertThrows(
                IllegalArgumentException.class,
                () -> service.stream("agent-1", request).blockLast());
        assertTrue(conversations.appended.isEmpty());
    }

    @Test
    void theApiIsStoppedBeforeASessionIsEvenOpened() {
        RecordingConversations conversations = new RecordingConversations(CHANNEL_KEY);
        AgentConversationRuntimeService service =
                new AgentConversationRuntimeService(
                        new EnabledAgent(), new ConversationService(conversations), null, null, null);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.stream("agent-1", new AgentRuntimeRequest("dev-staff", "WECOM:3f1c", "hi"))
                                .blockLast());
        assertEquals(0, conversations.opened);
    }

    /** Resolves every key to one stored session, as the case-insensitive unique key would. */
    private static final class RecordingConversations implements ConversationRepository {
        private final String storedKey;
        private final List<String> appended = new ArrayList<>();
        private int opened;

        private RecordingConversations(String storedKey) {
            this.storedKey = storedKey;
        }

        @Override
        public ConversationSession getOrCreateSession(String agentId, String userId, String sessionKey) {
            opened++;
            Instant now = Instant.now();
            return new ConversationSession("session-1", agentId, userId, storedKey, "ACTIVE", now, now, null);
        }

        @Override
        public Optional<ConversationSession> findSession(String agentId, String userId, String sessionKey) {
            return Optional.empty();
        }

        @Override
        public List<ConversationSession> findSessions(String agentId, String userId) {
            return List.of();
        }

        @Override
        public ConversationMessage appendMessage(
                String sessionId, ConversationRole role, String content, String source) {
            appended.add(content);
            throw new AssertionError("must not append to a channel session");
        }

        @Override
        public List<ConversationMessage> findMessages(String sessionId) {
            return List.of();
        }

        @Override
        public List<ConversationMessage> findRecentMessages(String sessionId, int limit) {
            return List.of();
        }
    }

    private static final class EnabledAgent implements AgentDefinitionRepository {
        @Override
        public Optional<AgentDefinition> findById(String id) {
            return Optional.of(
                    new AgentDefinition(id, "Agent", null, "prompt", "mock", "mock", List.of(), true, 1, null, null));
        }

        @Override
        public List<AgentDefinition> findAll() {
            return List.of();
        }

        @Override
        public AgentDefinition save(AgentDefinition definition) {
            return definition;
        }

        @Override
        public boolean existsById(String id) {
            return true;
        }

        @Override
        public void deleteById(String id) {}
    }
}
