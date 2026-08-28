package com.agentforge.runtime;

import com.agentforge.agent.AgentDefinition;
import com.agentforge.agent.AgentDefinitionRepository;
import com.agentforge.agent.AgentNotFoundException;
import com.agentforge.tool.AgentTool;
import com.agentforge.tool.ToolRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Flux;

public class AgentRuntimeManager implements AutoCloseable {

    private final AgentDefinitionRepository agentRepository;
    private final ToolRegistry toolRegistry;
    private final AgentRuntimeFactory runtimeFactory;
    private final Map<String, RuntimeHolder> runtimes = new ConcurrentHashMap<>();

    public AgentRuntimeManager(
            AgentDefinitionRepository agentRepository,
            ToolRegistry toolRegistry,
            AgentRuntimeFactory runtimeFactory) {
        this.agentRepository = agentRepository;
        this.toolRegistry = toolRegistry;
        this.runtimeFactory = runtimeFactory;
    }

    public Flux<AgentStreamChunk> stream(String agentId, AgentRuntimeRequest request) {
        AgentDefinition definition =
                agentRepository
                        .findById(agentId)
                        .orElseThrow(() -> new AgentNotFoundException(agentId));

        if (!definition.enabled()) {
            return Flux.error(new IllegalStateException("Agent is disabled: " + agentId));
        }

        AgentRuntime runtime = resolveRuntime(definition);
        return runtime.stream(request);
    }

    public void invalidate(String agentId) {
        RuntimeHolder removed = runtimes.remove(agentId);
        closeQuietly(removed);
    }

    public int cachedRuntimeCount() {
        return runtimes.size();
    }

    private AgentRuntime resolveRuntime(AgentDefinition definition) {
        RuntimeHolder holder =
                runtimes.compute(
                        definition.id(),
                        (id, current) -> {
                            if (current != null && current.version() == definition.version()) {
                                return current;
                            }

                            List<AgentTool> tools = toolRegistry.resolve(definition.toolNames());
                            AgentRuntime nextRuntime = runtimeFactory.create(definition, tools);
                            closeQuietly(current);
                            return new RuntimeHolder(definition.version(), nextRuntime);
                        });
        return holder.runtime();
    }

    private static void closeQuietly(RuntimeHolder holder) {
        if (holder == null || holder.runtime() == null) {
            return;
        }
        if (holder.runtime() instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Best-effort cleanup. A new runtime must not be blocked by stale cleanup.
            }
        }
    }

    @Override
    public void close() {
        runtimes.values().forEach(AgentRuntimeManager::closeQuietly);
        runtimes.clear();
    }

    private record RuntimeHolder(long version, AgentRuntime runtime) {
    }
}
