package com.agentforge.runtime;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import reactor.core.publisher.Flux;
import java.util.Objects;

public final class AgentScopeHarnessRuntime implements AgentRuntime, AutoCloseable {
    private static final String SOURCE = "agentscope-harness";
    private final HarnessAgent agent;
    public AgentScopeHarnessRuntime(String name, String systemPrompt, Model model) {
        Objects.requireNonNull(model, "model must not be null");
        this.agent = HarnessAgent.builder().name(name).sysPrompt(systemPrompt).model(model)
                .generateOptions(GenerateOptions.builder().build())
                .stateStore(new InMemoryAgentStateStore())
                .disableFilesystemTools().disableShellTool().disableDynamicSkills()
                .disableDefaultWorkspaceSkills().disableDynamicSubagents().disableMemoryTools()
                .disableMemoryHooks().disableWorkspaceContext().disableSubagents().disableToolsConfig().build();
    }
    @Override
    public Flux<AgentStreamChunk> stream(AgentRuntimeRequest request) {
        RuntimeContext context = RuntimeContext.builder()
                .userId(normalizeId(request.userId(), "anonymous"))
                .sessionId(normalizeId(request.sessionId(), "default")).build();
        Flux<AgentStreamChunk> content = agent.streamEvents(request.message(), context)
                .filter(event -> event.getType() == AgentEventType.TEXT_BLOCK_DELTA)
                .cast(TextBlockDeltaEvent.class).map(TextBlockDeltaEvent::getDelta)
                .filter(delta -> delta != null && !delta.isEmpty())
                .map(delta -> AgentStreamChunk.message(delta, SOURCE));
        return content.concatWithValues(AgentStreamChunk.done(SOURCE));
    }
    @Override public void close() { agent.close(); }
    private static String normalizeId(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
