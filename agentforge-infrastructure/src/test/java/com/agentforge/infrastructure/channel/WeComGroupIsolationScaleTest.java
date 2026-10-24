package com.agentforge.infrastructure.channel;

import static com.agentforge.infrastructure.channel.WeComChannelHarness.expected;
import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.infrastructure.channel.WeComChannelHarness.Options;
import com.agentforge.infrastructure.channel.WeComChannelHarness.Report;
import com.agentforge.runtime.AgentScopeHarnessRuntime;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.state.State;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * The isolation test at scale, plus capacity and memory measurements. Off in the regular build;
 * run with {@code -Dagentforge.scale=true -Dtest=WeComGroupIsolationScaleTest}, and set the size
 * with {@code -Dagentforge.scale.groups=10000}. Reads private AgentScope fields for diagnostics,
 * which is why it stays out of the regular build.
 */
@EnabledIfSystemProperty(named = "agentforge.scale", matches = "true")
class WeComGroupIsolationScaleTest {

    private static final int GROUPS = Integer.getInteger("agentforge.scale.groups", 10_000);

    @TempDir Path workspace;
    @TempDir Path stateFiles;

    @Test
    void groupsNeverSeeEachOthersHistoryAtScale() throws Exception {
        Options options = new Options();
        options.groups = GROUPS;
        options.memory = WeComGroupIsolationTest.memory(workspace);
        try (WeComChannelHarness harness = new WeComChannelHarness(options)) {
            Report report = WeComGroupIsolationTest.runWavesAndBursts(harness, 2, 3);
            report.extra.put("agent caches", cacheSizes(harness));
            report.print();

            assertEquals(0, report.crossGroup.get());
            assertEquals(0, report.wrongHistory.get());
            assertEquals(0, report.unfinished.get());
            assertEquals(0, harness.storedMemoryViolations());
            assertEquals(List.of(), WeComGroupIsolationTest.filesUnder(workspace));
        }
    }

    @Test
    void shippedDefaultsUnderABurstOfAllGroups() throws Exception {
        // Shipped defaults: 4 workers, at most 1000 waiting turns, 3 per session, admission by
        // deadline; a model taking 300 ms is still far faster than a real one.
        Options options = new Options();
        options.groups = GROUPS;
        options.workers = 4;
        options.maxPending = 1000;
        options.modelLatencyMillis = 300;
        options.expectedTurn = Duration.ofMillis(300);
        options.memory = WeComGroupIsolationTest.memory(workspace);
        try (WeComChannelHarness harness = new WeComChannelHarness(options)) {
            Report report = new Report("shipped defaults, one message per group at once");
            long started = System.nanoTime();
            Map<Integer, JsonNode> firstReplies = harness.sendAll(1, report);
            harness.drain();
            report.phase("burst", GROUPS, started);
            int answered = 0;
            for (int i = 0; i < GROUPS; i++) {
                if (!firstReplies.get(i).path("finish").asBoolean()) {
                    answered++;
                    report.check(i, harness.finalAnswer(firstReplies.get(i)), expected(i, 1));
                }
            }
            Map<String, Integer> outcomes = harness.inboundOutcomes();
            report.extra.put("answered", answered + " of " + GROUPS);
            report.extra.put("inbound outcomes", outcomes);
            report.print();

            assertEquals(0, report.crossGroup.get());
            assertEquals(0, outcomes.getOrDefault("FAILED/EXPIRED_IN_QUEUE", 0));
            assertEquals(0, outcomes.getOrDefault("FAILED/TURN_TIMEOUT", 0));
        }
    }

    /**
     * What the shared agent keeps per session in the JVM, with a state store that serializes like
     * Redis does. With an in-memory store both would hold the same objects and nothing could be
     * attributed to the caches.
     */
    @Test
    void agentCacheFootprintPerSession() throws Exception {
        Options options = new Options();
        options.groups = GROUPS;
        options.memory = WeComGroupIsolationTest.memory(workspace);
        options.stateStore = new FileBackedStore(stateFiles);
        try (WeComChannelHarness harness = new WeComChannelHarness(options)) {
            Report report = WeComGroupIsolationTest.runWavesAndBursts(harness, 2, 3);
            Map<String, Object> caches = cacheSizes(harness);
            long before = usedHeapAfterGc();
            clearCaches(harness);
            long after = usedHeapAfterGc();
            caches.put("held by the two caches", String.format("%.1f MB", (before - after) / 1048576.0));
            caches.put("per session", String.format("%.1f KB", (before - after) / 1024.0 / GROUPS));
            report.extra.put("agent caches", caches);
            report.print();

            assertEquals(0, report.crossGroup.get());
            assertEquals(0, report.wrongHistory.get());
        }
    }

    /** With generations, the caches hold at most one generation's sessions, not every group's. */
    @Test
    void generationsBoundTheAgentCaches() throws Exception {
        Options options = new Options();
        options.groups = GROUPS;
        options.memory = WeComGroupIsolationTest.memory(workspace);
        options.generationTurns = 2000;
        try (WeComChannelHarness harness = new WeComChannelHarness(options)) {
            Report report = WeComGroupIsolationTest.runWavesAndBursts(harness, 2, 3);
            Map<String, Object> caches = cacheSizes(harness);
            report.extra.put("generations", harness.generations.get());
            report.extra.put("agent caches of the current generation", caches);
            report.print();

            assertEquals(0, report.crossGroup.get());
            assertEquals(0, report.wrongHistory.get());
            assertEquals(0, report.unfinished.get());
            // At most one generation's sessions, plus the default slot the agent creates up front.
            assertTrue((Integer) caches.get("stateCache") <= 2000 + 1, "caches bounded by a generation");
            assertTrue((Integer) caches.get("permissionEngineCache") <= 2000, "caches bounded by a generation");
        }
    }

    private static Map<String, Object> cacheSizes(WeComChannelHarness harness) throws Exception {
        Object react = reactAgent(harness);
        Map<String, Object> sizes = new LinkedHashMap<>();
        sizes.put("stateCache", ((Map<?, ?>) field(react, react.getClass(), "stateCache")).size());
        sizes.put("permissionEngineCache", ((Map<?, ?>) field(react, react.getClass(), "permissionEngineCache")).size());
        sizes.put("callGates", ((Map<?, ?>) field(react, react.getClass().getSuperclass(), "callGates")).size());
        return sizes;
    }

    private static void clearCaches(WeComChannelHarness harness) throws Exception {
        Object react = reactAgent(harness);
        ((Map<?, ?>) field(react, react.getClass(), "stateCache")).clear();
        ((Map<?, ?>) field(react, react.getClass(), "permissionEngineCache")).clear();
    }

    private static Object reactAgent(WeComChannelHarness harness) throws Exception {
        Object agent = field(harness.runtime.get(), AgentScopeHarnessRuntime.class, "agent");
        return agent.getClass().getMethod("getDelegate").invoke(agent);
    }

    private static Object field(Object target, Class<?> owner, String name) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static long usedHeapAfterGc() throws InterruptedException {
        Runtime jvm = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(200);
        }
        return jvm.totalMemory() - jvm.freeMemory();
    }

    /** JSON files instead of Redis; ':' in user ids is not a legal file name on every system. */
    static final class FileBackedStore implements AgentStateStore {
        private final JsonFileAgentStateStore files;

        FileBackedStore(Path root) {
            this.files = new JsonFileAgentStateStore(root);
        }

        private static String safe(String userId) {
            return userId == null ? null : userId.replace(':', '_');
        }

        @Override
        public void save(String userId, String sessionId, String key, State value) {
            files.save(safe(userId), sessionId, key, value);
        }

        @Override
        public void save(String userId, String sessionId, String key, List<? extends State> values) {
            files.save(safe(userId), sessionId, key, values);
        }

        @Override
        public <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type) {
            return files.get(safe(userId), sessionId, key, type);
        }

        @Override
        public <T extends State> List<T> getList(String userId, String sessionId, String key, Class<T> type) {
            return files.getList(safe(userId), sessionId, key, type);
        }

        @Override
        public boolean exists(String userId, String sessionId) {
            return files.exists(safe(userId), sessionId);
        }

        @Override
        public void delete(String userId, String sessionId) {
            files.delete(safe(userId), sessionId);
        }

        @Override
        public Set<String> listSessionIds(String userId) {
            return files.listSessionIds(safe(userId));
        }
    }
}
