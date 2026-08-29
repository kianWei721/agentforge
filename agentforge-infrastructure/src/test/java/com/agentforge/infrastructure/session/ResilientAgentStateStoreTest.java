package com.agentforge.infrastructure.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import org.junit.jupiter.api.Test;

class ResilientAgentStateStoreTest {

    @Test
    void shouldUseJvmFallbackWhenRedisSaveAndReadFail() {
        AgentStateStore primary = mock(AgentStateStore.class);
        InMemoryAgentStateStore fallback = new InMemoryAgentStateStore();
        ResilientAgentStateStore store =
                new ResilientAgentStateStore(primary, fallback);

        doThrow(new RuntimeException("redis unavailable"))
                .when(primary)
                .save(eq("user-1"), eq("session-1"), eq("agent_state"), any(AgentState.class));

        AgentState state =
                AgentState.builder()
                        .userId("user-1")
                        .sessionId("session-1")
                        .build();

        store.save("user-1", "session-1", "agent_state", state);

        when(primary.get(
                        eq("user-1"),
                        eq("session-1"),
                        eq("agent_state"),
                        eq(AgentState.class)))
                .thenThrow(new RuntimeException("redis unavailable"));

        AgentState restored =
                store.get("user-1", "session-1", "agent_state", AgentState.class)
                        .orElseThrow();

        assertEquals("session-1", restored.getSessionId());
        assertEquals("user-1", restored.getUserId());
    }

    @Test
    void shouldPreferRedisAndDiscardStaleFallbackWhenPrimaryRecovers() {
        AgentStateStore primary = mock(AgentStateStore.class);
        InMemoryAgentStateStore fallback = new InMemoryAgentStateStore();
        ResilientAgentStateStore store =
                new ResilientAgentStateStore(primary, fallback);

        AgentState stale =
                AgentState.builder()
                        .userId("user-1")
                        .sessionId("session-1")
                        .summary("stale")
                        .build();
        fallback.save("user-1", "session-1", "agent_state", stale);

        AgentState current =
                AgentState.builder()
                        .userId("user-1")
                        .sessionId("session-1")
                        .summary("current")
                        .build();

        when(primary.get(
                        eq("user-1"),
                        eq("session-1"),
                        eq("agent_state"),
                        eq(AgentState.class)))
                .thenReturn(java.util.Optional.of(current));

        AgentState restored =
                store.get("user-1", "session-1", "agent_state", AgentState.class)
                        .orElseThrow();

        assertEquals("current", restored.getSummary());
        assertFalse(fallback.exists("user-1", "session-1"));
    }
}
