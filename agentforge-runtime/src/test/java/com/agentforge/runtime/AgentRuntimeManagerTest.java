package com.agentforge.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.agent.AgentDefinition;
import com.agentforge.agent.AgentDefinitionRepository;
import com.agentforge.tool.DefaultToolRegistry;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

class AgentRuntimeManagerTest {

    private final AtomicLong version = new AtomicLong(1);
    // Written on the runtime's scheduler, read by the test thread.
    private final List<FakeRuntime> created = new CopyOnWriteArrayList<>();
    private Sinks.Many<AgentStreamChunk> openTurn;
    private boolean failToStart;

    @Test
    void aGenerationIsReplacedAfterItsTurnsAndTheOldOneClosed() {
        AgentRuntimeManager manager = manager(2);

        run(manager);
        run(manager);
        assertEquals(1, created.size());

        run(manager);
        assertEquals(2, created.size(), "third turn starts a new generation");
        // Released in doFinally, which runs after blockLast has already returned: wait for it.
        waitFor(() -> created.get(0).closed.get());
        assertFalse(created.get(1).closed.get());
    }

    @Test
    void anOldGenerationClosesOnlyAfterItsRunningTurnEnds() {
        AgentRuntimeManager manager = manager(1);
        openTurn = Sinks.many().unicast().onBackpressureBuffer();
        Disposable running = manager.stream("agent", request()).subscribe();
        waitFor(() -> created.size() == 1 && created.get(0).subscribed.get());

        openTurn = null;
        run(manager);

        assertEquals(2, created.size());
        assertFalse(created.get(0).closed.get(), "a turn is still running on it");
        created.get(0).sink.tryEmitComplete();
        waitFor(() -> created.get(0).closed.get());
        running.dispose();
    }

    @Test
    void aChangedDefinitionAlsoWaitsForRunningTurns() {
        AgentRuntimeManager manager = manager(0);
        openTurn = Sinks.many().unicast().onBackpressureBuffer();
        Disposable running = manager.stream("agent", request()).subscribe();
        waitFor(() -> created.size() == 1 && created.get(0).subscribed.get());

        version.incrementAndGet();
        openTurn = null;
        run(manager);

        assertEquals(2, created.size());
        assertFalse(created.get(0).closed.get());
        created.get(0).sink.tryEmitComplete();
        waitFor(() -> created.get(0).closed.get());
        running.dispose();
    }

    @Test
    void aTurnThatFailsToStartStillLetsItsGenerationClose() {
        AgentRuntimeManager manager = manager(1);
        failToStart = true;
        assertThrows(IllegalStateException.class, () -> run(manager));

        failToStart = false;
        run(manager);

        assertEquals(2, created.size());
        assertTrue(created.get(0).closed.get(), "no turn is left running on the failed generation");
    }

    @Test
    void zeroNeverReplacesTheRuntime() {
        AgentRuntimeManager manager = manager(0);
        for (int i = 0; i < 20; i++) {
            run(manager);
        }
        assertEquals(1, created.size());
    }

    private AgentRuntimeManager manager(long turnsPerGeneration) {
        AgentDefinitionRepository agents =
                new AgentDefinitionRepository() {
                    @Override
                    public Optional<AgentDefinition> findById(String id) {
                        return Optional.of(
                                new AgentDefinition(
                                        id, "Agent", null, "prompt", "fake", "fake", List.of(), true,
                                        version.get(), null, null));
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
                };
        return new AgentRuntimeManager(
                agents,
                new DefaultToolRegistry(),
                (definition, tools) -> {
                    FakeRuntime runtime = new FakeRuntime(openTurn, failToStart);
                    created.add(runtime);
                    return runtime;
                },
                turnsPerGeneration);
    }

    private static void run(AgentRuntimeManager manager) {
        manager.stream("agent", request()).blockLast();
    }

    private static AgentRuntimeRequest request() {
        return new AgentRuntimeRequest("user", "session", "hi");
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("condition not reached");
            }
            Thread.onSpinWait();
        }
    }

    private static final class FakeRuntime implements AgentRuntime, AutoCloseable {
        private final Sinks.Many<AgentStreamChunk> sink;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean subscribed = new AtomicBoolean();
        private final boolean failToStart;

        private FakeRuntime(Sinks.Many<AgentStreamChunk> sink, boolean failToStart) {
            this.sink = sink;
            this.failToStart = failToStart;
        }

        @Override
        public Flux<AgentStreamChunk> stream(AgentRuntimeRequest request) {
            if (failToStart) {
                throw new IllegalStateException("cannot start");
            }
            subscribed.set(true);
            return sink == null ? Flux.just(AgentStreamChunk.done("fake")) : sink.asFlux();
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
