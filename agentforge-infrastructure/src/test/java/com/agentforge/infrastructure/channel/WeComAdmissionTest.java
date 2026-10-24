package com.agentforge.infrastructure.channel;

import static com.agentforge.infrastructure.channel.WeComChannelHarness.expected;
import static com.agentforge.infrastructure.channel.WeComChannelHarness.marker;
import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.infrastructure.channel.WeComChannelHarness.Options;
import com.agentforge.infrastructure.channel.WeComChannelHarness.Report;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Admission by deadline. Messages arrive at random (Poisson) at about three times what the
 * workers can answer inside the reply window. Whatever is accepted must be answered inside the
 * window; whatever cannot be must be refused in the callback itself, never accepted and left to
 * expire.
 *
 * <p>The runtime is warmed up first: the first turn builds the agent and loads its classes, which
 * a turn estimate of a few hundred milliseconds does not cover. A real deployment starts from a
 * 15 s estimate, against which that is noise. For the same reason turns take 300 ms here rather
 * than less: on a two-core machine the in-process work around a turn (crypto, H2, AgentScope)
 * would otherwise be as large as the turn itself, while a real turn is a remote model call of
 * seconds.
 */
class WeComAdmissionTest {

    private static final int GROUPS = 400;
    private static final int WARM_UP = 8;

    @TempDir Path workspace;

    @Test
    void acceptedMessagesFinishInTimeAndTheRestAreRefusedAtOnce() throws Exception {
        try (WeComChannelHarness harness = new WeComChannelHarness(options())) {
            Report report = new Report("deadline admission, Poisson arrivals");
            Result result = arrive(harness, report, 20, 1);

            assertTrue(result.accepted > 0, "some messages are answered");
            assertTrue(result.refused > 0, "the overload is refused, not queued");
            assertAllAcceptedAnsweredInTime(result);
            List<JsonNode> replies = result.acceptedReplies;
            for (int k = 0; k < replies.size(); k++) {
                int group = result.acceptedGroups.get(k);
                report.check(group, harness.finalAnswer(replies.get(k)), expected(group, 1));
            }
            assertEquals(0, report.crossGroup.get());
            assertEquals(0, report.wrongHistory.get());
            assertEquals(0, report.unfinished.get());
        }
    }

    /**
     * Group chat: each group asks three questions in a row while the workers are overloaded. A
     * follow-up runs right after its group's turn, and admission counts the group's own turns ahead
     * of it, so these too are answered in time or refused at once.
     */
    @Test
    void followUpsUnderOverloadAreAnsweredInTimeOrRefusedAtOnce() throws Exception {
        try (WeComChannelHarness harness = new WeComChannelHarness(options())) {
            Report report = new Report("deadline admission, three questions per group");
            Result result = arrive(harness, report, 20.0 / 3, 3);

            assertTrue(result.accepted > 0, "some messages are answered");
            assertTrue(result.refused > 0, "the overload is refused, not queued");
            assertAllAcceptedAnsweredInTime(result);
            assertEquals(0, harness.storedMemoryViolations());
        }
    }

    private Options options() {
        Options options = new Options();
        options.memory = WeComGroupIsolationTest.memory(workspace);
        options.groups = GROUPS + WARM_UP;
        options.workers = 2;
        options.modelLatencyMillis = 300;
        options.expectedTurn = Duration.ofMillis(300);
        options.replyWindow = Duration.ofMillis(4500);
        return options;
    }

    /**
     * Groups arrive at {@code groupsPerSecond} for four seconds; each sends {@code questions}. Two
     * workers answer about seven turns a second, so the callers offer about three times that.
     */
    private Result arrive(WeComChannelHarness harness, Report report, double groupsPerSecond, int questions)
            throws Exception {
        // One at a time and to the end, so warm-up turns never compete with each other.
        for (int group = GROUPS; group < GROUPS + WARM_UP; group++) {
            harness.finalAnswer(harness.send(group, marker(group, 1), null));
        }
        harness.drain();
        Map<String, Integer> warmUp = harness.inboundOutcomes();

        long durationMillis = 4000;
        Random random = new Random(7);
        List<Integer> groups = new ArrayList<>();
        List<Future<JsonNode>> firstReplies = new ArrayList<>();
        long start = System.nanoTime();
        double at = 0;
        for (int group = 0; at < durationMillis && group < GROUPS; group++) {
            at += -Math.log(1 - random.nextDouble()) / groupsPerSecond * 1000;
            long sleep = (long) at - (System.nanoTime() - start) / 1_000_000;
            if (sleep > 0) {
                Thread.sleep(sleep);
            }
            for (int q = 1; q <= questions; q++) {
                groups.add(group);
                firstReplies.add(harness.sendAsync(group, marker(group, q), report));
            }
        }

        Result result = new Result();
        for (int k = 0; k < firstReplies.size(); k++) {
            JsonNode first = firstReplies.get(k).get(1, TimeUnit.MINUTES);
            if (first.path("finish").asBoolean()) {
                result.refused++;
            } else {
                result.accepted++;
                result.acceptedGroups.add(groups.get(k));
                result.acceptedReplies.add(first);
            }
        }
        harness.drain();

        result.outcomes = harness.inboundOutcomes();
        warmUp.forEach((outcome, count) -> result.outcomes.merge(outcome, -count, Integer::sum));
        result.outcomes.values().removeIf(count -> count == 0);
        report.extra.put("messages", firstReplies.size());
        report.extra.put("accepted / refused at once", result.accepted + " / " + result.refused);
        report.extra.put("inbound outcomes", result.outcomes);
        report.extra.put("warm-up outcomes", warmUp);
        report.extra.put("measured average turn", harness.gate.averageTurn().toMillis() + " ms");
        report.extra.put(
                "late turns (arrival order, ms from first measured arrival to finish)",
                harness.jdbc.queryForList(
                        "SELECT msg_id, DATEDIFF('MILLISECOND', (SELECT MIN(created_at) FROM af_channel_inbound"
                                + " WHERE CAST(SUBSTRING(msg_id, 3) AS INT) > ?), created_at) AS arrived_ms,"
                                + " DATEDIFF('MILLISECOND', created_at, updated_at) AS took_ms"
                                + " FROM af_channel_inbound WHERE error_code IN ('TURN_TIMEOUT','EXPIRED_IN_QUEUE')"
                                + " AND CAST(SUBSTRING(msg_id, 3) AS INT) > ? ORDER BY created_at",
                        WARM_UP,
                        WARM_UP));
        report.print();
        return result;
    }

    private static void assertAllAcceptedAnsweredInTime(Result result) {
        Map<String, Integer> outcomes = result.outcomes;
        assertEquals(0, outcomes.getOrDefault("FAILED/EXPIRED_IN_QUEUE", 0), "accepted, then expired in the queue");
        assertEquals(0, outcomes.getOrDefault("FAILED/TURN_TIMEOUT", 0), "accepted, then cut off by the window");
        assertEquals(result.accepted, outcomes.getOrDefault("DONE", 0));
    }

    private static final class Result {
        int accepted;
        int refused;
        final List<Integer> acceptedGroups = new ArrayList<>();
        final List<JsonNode> acceptedReplies = new ArrayList<>();
        Map<String, Integer> outcomes;
    }
}
