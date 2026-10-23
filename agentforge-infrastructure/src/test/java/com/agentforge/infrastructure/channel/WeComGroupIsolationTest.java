package com.agentforge.infrastructure.channel;

import static com.agentforge.infrastructure.channel.WeComChannelHarness.expected;
import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.infrastructure.channel.WeComChannelHarness.Options;
import com.agentforge.infrastructure.channel.WeComChannelHarness.Report;
import com.agentforge.runtime.AgentRuntimeRequest;
import com.agentforge.runtime.HarnessMemoryOptions;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Session and memory isolation of WeCom groups through the real chain, small enough for every
 * build. {@link WeComGroupIsolationScaleTest} runs the same at ten thousand groups on demand.
 */
class WeComGroupIsolationTest {

    private static final int WAVES = 2;
    private static final int BURST = 3;

    @TempDir Path workspace;

    @Test
    void groupsNeverSeeEachOthersHistory() throws Exception {
        Options options = new Options();
        options.groups = 50;
        options.memory = memory(workspace);
        try (WeComChannelHarness harness = new WeComChannelHarness(options)) {
            Report report = runWavesAndBursts(harness, WAVES, BURST);
            report.print();

            assertEquals(0, report.crossGroup.get(), "answers containing another group's history");
            assertEquals(0, report.wrongHistory.get(), "answers with missing or reordered own history");
            assertEquals(0, report.unfinished.get(), "answers that never finished");
            assertEquals(0, harness.storedMemoryViolations(), "stored messages under the wrong group");
        }
    }

    @Test
    void replacingTheRuntimeMidRunLosesAndLeaksNothing() throws Exception {
        Options options = new Options();
        options.groups = 50;
        options.memory = memory(workspace);
        // Not a divisor of the 50-group waves, so generations change while turns are running.
        options.generationTurns = 17;
        try (WeComChannelHarness harness = new WeComChannelHarness(options)) {
            Report report = runWavesAndBursts(harness, WAVES, BURST);
            report.extra.put("generations", harness.generations.get());
            report.print();

            assertTrue(harness.generations.get() > 5, "the runtime was replaced repeatedly");
            assertEquals(0, report.crossGroup.get());
            assertEquals(0, report.wrongHistory.get(), "history survives a new generation");
            assertEquals(0, report.unfinished.get());
            assertEquals(0, harness.storedMemoryViolations());
        }
    }

    @Test
    void aLongConversationStaysBoundedAndWritesNothingToDisk() throws Exception {
        Options options = new Options();
        options.groups = 1;
        // Low thresholds so a few dozen turns go through several compactions.
        options.memory = new HarnessMemoryOptions(workspace, 20, 2000, 8, 200);
        options.modelOutputCapChars = 200;
        int turns = 100;
        String filler = "这是一段为了让上下文变长的客户描述。".repeat(10);
        try (WeComChannelHarness harness = new WeComChannelHarness(options)) {
            for (int t = 1; t <= turns; t++) {
                // A user id with no character a file system would reject: any write would succeed.
                harness.conversationRuntime
                        .stream(
                                WeComChannelHarness.AGENT,
                                new AgentRuntimeRequest(
                                        "long-user", "long-session", "问题 G0-T" + t + " " + filler))
                        .blockLast();
            }

            int growthWithoutBound = turns * (filler.length() + 200);
            System.out.printf(
                    "%n===== long conversation =====%nturns=%d model calls=%d largest prompt=%d chars"
                            + " (unbounded would reach ~%d)%n",
                    turns, harness.model.calls.get(), harness.model.maxPromptChars, growthWithoutBound);

            assertTrue(harness.model.calls.get() > turns, "compaction ran, which asks the model for summaries");
            assertTrue(
                    harness.model.maxPromptChars < 15_000,
                    "context stays bounded, got " + harness.model.maxPromptChars + " chars");
            assertEquals(List.of(), filesUnder(workspace), "conversation content written to disk");
        }
    }

    static Report runWavesAndBursts(WeComChannelHarness harness, int waves, int burst) throws Exception {
        int groups = harness.options.groups;
        Report report = new Report("isolation, " + groups + " groups");
        for (int turn = 1; turn <= waves; turn++) {
            long started = System.nanoTime();
            Map<Integer, JsonNode> firstReplies = harness.sendAll(turn, report);
            harness.drain();
            report.phase("wave " + turn, groups, started);
            for (int i = 0; i < groups; i++) {
                report.check(i, harness.finalAnswer(firstReplies.get(i)), expected(i, turn));
            }
        }
        // Each group sends several messages back to back; the gate must keep them in order.
        long started = System.nanoTime();
        Map<Integer, List<JsonNode>> bursts = harness.sendBursts(waves, burst, report);
        harness.drain();
        report.phase("burst x" + burst, groups * burst, started);
        for (int i = 0; i < groups; i++) {
            List<JsonNode> firstReplies = bursts.get(i);
            for (int j = 0; j < firstReplies.size(); j++) {
                report.check(i, harness.finalAnswer(firstReplies.get(j)), expected(i, waves + j + 1));
            }
        }
        report.extra.put("inbound outcomes", harness.inboundOutcomes());
        return report;
    }

    static HarnessMemoryOptions memory(Path workspace) {
        HarnessMemoryOptions defaults = HarnessMemoryOptions.defaults();
        return new HarnessMemoryOptions(
                workspace,
                defaults.compactionTriggerMessages(),
                defaults.compactionTriggerTokens(),
                defaults.compactionKeepMessages(),
                defaults.summaryMaxChars());
    }

    static List<Path> filesUnder(Path root) throws Exception {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).toList();
        }
    }
}
