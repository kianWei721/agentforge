package com.agentforge.infrastructure.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.agentforge.conversation.ConversationMessage;
import com.agentforge.conversation.ConversationRepository;
import com.agentforge.conversation.ConversationRole;
import com.agentforge.conversation.ConversationService;
import com.agentforge.runtime.SessionContextRestoreService;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionContextRestoreServiceTest {

    @Test
    void shouldHydrateMysqlTranscriptOnlyOnCacheMiss() {
        ConversationRepository repository = mock(ConversationRepository.class);
        when(repository.findRecentMessages("session-1", 3))
                .thenReturn(
                        List.of(
                                message("m1", 1, ConversationRole.USER, "remember 4821"),
                                message("m2", 2, ConversationRole.ASSISTANT, "ACK")));

        ConversationService conversationService = new ConversationService(repository);
        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        SessionContextRestoreService service =
                new SessionContextRestoreService(conversationService, stateStore, 3);

        SessionContextRestoreService.ContextRestoreResult first =
                service.ensureContext("user-1", "session-1");

        assertEquals(
                SessionContextRestoreService.ContextRestoreSource.MYSQL_TRANSCRIPT,
                first.source());
        assertEquals(2, first.restoredMessages());

        AgentState state =
                stateStore
                        .get("user-1", "session-1", "agent_state", AgentState.class)
                        .orElseThrow();
        assertEquals(2, state.getContext().size());
        assertEquals(MsgRole.USER, state.getContext().get(0).getRole());
        assertEquals("remember 4821", state.getContext().get(0).getTextContent());
        assertEquals(MsgRole.ASSISTANT, state.getContext().get(1).getRole());
        assertEquals("ACK", state.getContext().get(1).getTextContent());

        SessionContextRestoreService.ContextRestoreResult second =
                service.ensureContext("user-1", "session-1");

        assertEquals(SessionContextRestoreService.ContextRestoreSource.CACHE, second.source());
        verify(repository, times(1)).findRecentMessages("session-1", 3);
    }

    @Test
    void shouldCreateEmptyStateForNewSession() {
        ConversationRepository repository = mock(ConversationRepository.class);
        when(repository.findRecentMessages("session-new", 10)).thenReturn(List.of());

        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        SessionContextRestoreService service =
                new SessionContextRestoreService(
                        new ConversationService(repository),
                        stateStore,
                        10);

        SessionContextRestoreService.ContextRestoreResult result =
                service.ensureContext("user-1", "session-new");

        assertEquals(SessionContextRestoreService.ContextRestoreSource.EMPTY, result.source());
        AgentState state =
                stateStore
                        .get("user-1", "session-new", "agent_state", AgentState.class)
                        .orElseThrow();
        assertTrue(state.getContext().isEmpty());
    }

    private static ConversationMessage message(
            String id, long sequenceNo, ConversationRole role, String content) {
        return new ConversationMessage(
                id,
                "session-1",
                sequenceNo,
                role,
                content,
                role == ConversationRole.USER ? "api" : "agentscope-harness",
                Instant.parse("2026-08-29T00:00:00Z").plusSeconds(sequenceNo));
    }
}
