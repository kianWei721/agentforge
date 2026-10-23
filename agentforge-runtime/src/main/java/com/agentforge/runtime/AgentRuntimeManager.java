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
import reactor.core.scheduler.Schedulers;

/**
 * One runtime per agent, replaced by a new generation after a number of turns.
 *
 * <p>The AgentScope agent caches state for every session it has served and never evicts it;
 * across thousands of WeCom groups that grows without bound. Since each turn reloads its state
 * from the state store, a fresh runtime loses nothing, so after {@code maxTurnsPerGeneration}
 * turns the next turn starts a new generation and the old one closes once its in-flight turns
 * end. A changed agent definition retires the old runtime the same way.
 */
public class AgentRuntimeManager implements AutoCloseable {

    private final AgentDefinitionRepository agentRepository;
    private final ToolRegistry toolRegistry;
    private final AgentRuntimeFactory runtimeFactory;
    private final long maxTurnsPerGeneration;
    private final Map<String, Generation> runtimes = new ConcurrentHashMap<>();

    public AgentRuntimeManager(
            AgentDefinitionRepository agentRepository,
            ToolRegistry toolRegistry,
            AgentRuntimeFactory runtimeFactory) {
        this(agentRepository, toolRegistry, runtimeFactory, 0);
    }

    /** @param maxTurnsPerGeneration turns before a runtime is replaced; 0 never replaces it */
    public AgentRuntimeManager(
            AgentDefinitionRepository agentRepository,
            ToolRegistry toolRegistry,
            AgentRuntimeFactory runtimeFactory,
            long maxTurnsPerGeneration) {
        if (maxTurnsPerGeneration < 0) {
            throw new IllegalArgumentException("maxTurnsPerGeneration must not be negative");
        }
        this.agentRepository = agentRepository;
        this.toolRegistry = toolRegistry;
        this.runtimeFactory = runtimeFactory;
        this.maxTurnsPerGeneration = maxTurnsPerGeneration;
    }

    public Flux<AgentStreamChunk> stream(String agentId, AgentRuntimeRequest request) {
        return Flux.defer(
                        () -> {
                            AgentDefinition definition =
                                    agentRepository
                                            .findById(agentId)
                                            .orElseThrow(() -> new AgentNotFoundException(agentId));

                            if (!definition.enabled()) {
                                return Flux.error(
                                        new IllegalStateException("Agent is disabled: " + agentId));
                            }

                            Generation generation = acquire(definition);
                            Flux<AgentStreamChunk> turn;
                            try {
                                turn = generation.runtime.stream(request);
                            } catch (RuntimeException failedToStart) {
                                // No Flux to release on: release here, or the generation never closes.
                                generation.release();
                                throw failedToStart;
                            }
                            return turn.doFinally(signal -> generation.release());
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    public void invalidate(String agentId) {
        Generation removed = runtimes.remove(agentId);
        if (removed != null) {
            removed.retire();
        }
    }

    public int cachedRuntimeCount() {
        return runtimes.size();
    }

    /** Takes a turn on the current generation, starting a new one when it is stale or used up. */
    private Generation acquire(AgentDefinition definition) {
        Generation[] taken = new Generation[1];
        runtimes.compute(
                definition.id(),
                (id, current) -> {
                    Generation generation = current;
                    if (generation == null
                            || generation.version != definition.version()
                            || (maxTurnsPerGeneration > 0
                                    && generation.turnsStarted >= maxTurnsPerGeneration)) {
                        List<AgentTool> tools = toolRegistry.resolve(definition.toolNames());
                        generation =
                                new Generation(
                                        definition.version(), runtimeFactory.create(definition, tools));
                        if (current != null) {
                            current.retire();
                        }
                    }
                    generation.begin();
                    taken[0] = generation;
                    return generation;
                });
        return taken[0];
    }

    @Override
    public void close() {
        runtimes.values().forEach(Generation::closeNow);
        runtimes.clear();
    }

    /** A runtime with the turns it has started and the ones still running on it. */
    private static final class Generation {
        private final long version;
        private final AgentRuntime runtime;
        private long turnsStarted;
        private int inFlight;
        private boolean retired;
        private boolean closed;

        private Generation(long version, AgentRuntime runtime) {
            this.version = version;
            this.runtime = runtime;
        }

        private synchronized void begin() {
            turnsStarted++;
            inFlight++;
        }

        private void release() {
            boolean close;
            synchronized (this) {
                inFlight--;
                close = retired && inFlight == 0 && !closed;
                closed |= close;
            }
            if (close) {
                closeQuietly(runtime);
            }
        }

        /** No new turns; closes once the running ones end. */
        private void retire() {
            boolean close;
            synchronized (this) {
                retired = true;
                close = inFlight == 0 && !closed;
                closed |= close;
            }
            if (close) {
                closeQuietly(runtime);
            }
        }

        private void closeNow() {
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
            }
            closeQuietly(runtime);
        }
    }

    private static void closeQuietly(AgentRuntime runtime) {
        if (runtime instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Best-effort cleanup. A new runtime must not be blocked by stale cleanup.
            }
        }
    }
}
