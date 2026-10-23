package com.agentforge.channel.wecom;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.channel.ChannelMetrics;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SessionTurnGateTest {

    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void turnsOfOneSessionRunOneAtATimeInArrivalOrder() throws Exception {
        SessionTurnGate gate = new SessionTurnGate(pool, 64, 10);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        List<Integer> order = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(5);

        for (int i = 0; i < 5; i++) {
            int turn = i;
            assertEquals(
                    SessionTurnGate.Admission.ACCEPTED,
                    gate.submit(
                            "session-a",
                            () -> {
                                maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
                                sleep(20);
                                order.add(turn);
                                running.decrementAndGet();
                                done.countDown();
                            }));
        }

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(1, maxRunning.get());
        assertEquals(List.of(0, 1, 2, 3, 4), order);
        waitUntilIdle(gate);
    }

    @Test
    void differentSessionsRunInParallel() throws Exception {
        SessionTurnGate gate = new SessionTurnGate(pool, 64, 10);
        CountDownLatch bothRunning = new CountDownLatch(2);
        CountDownLatch done = new CountDownLatch(2);

        for (String session : List.of("session-a", "session-b")) {
            gate.submit(
                    session,
                    () -> {
                        bothRunning.countDown();
                        try {
                            // Only completes if the other session's turn is running at the same time.
                            if (bothRunning.await(2, TimeUnit.SECONDS)) {
                                done.countDown();
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    });
        }

        assertTrue(done.await(5, TimeUnit.SECONDS));
    }

    @Test
    void aSessionWithTooManyWaitingTurnsIsBusy() {
        Queue<Runnable> scheduled = new ArrayDeque<>();
        SessionTurnGate gate = new SessionTurnGate(scheduled::add, 64, 2);

        assertEquals(SessionTurnGate.Admission.ACCEPTED, gate.submit("s", () -> {}));
        assertEquals(SessionTurnGate.Admission.ACCEPTED, gate.submit("s", () -> {}));
        assertEquals(SessionTurnGate.Admission.ACCEPTED, gate.submit("s", () -> {}));
        assertEquals(SessionTurnGate.Admission.SESSION_BUSY, gate.submit("s", () -> {}));
        // Another session is unaffected.
        assertEquals(SessionTurnGate.Admission.ACCEPTED, gate.submit("other", () -> {}));
        // Only the head of each session reaches the executor.
        assertEquals(2, scheduled.size());
    }

    @Test
    void tooManyWaitingTurnsOverallIsOverload() {
        SessionTurnGate gate = new SessionTurnGate(runnable -> {}, 2, 10);

        assertEquals(SessionTurnGate.Admission.ACCEPTED, gate.submit("a", () -> {}));
        assertEquals(SessionTurnGate.Admission.ACCEPTED, gate.submit("b", () -> {}));
        assertEquals(SessionTurnGate.Admission.OVERLOADED, gate.submit("c", () -> {}));
    }

    @Test
    void aTurnThatCouldNotFinishInTimeIsRefusedAtOnce() {
        Queue<Runnable> scheduled = new ArrayDeque<>();
        SessionTurnGate gate =
                new SessionTurnGate(scheduled::add, 1, 64, 10, Duration.ofMillis(100), ChannelMetrics.NONE);

        // A free worker: admitted.
        assertEquals(SessionTurnGate.Admission.ACCEPTED, gate.submit("a", Duration.ofSeconds(1), () -> {}));
        // One turn ahead, its own and one of margin: 300 ms of a second.
        assertEquals(SessionTurnGate.Admission.ACCEPTED, gate.submit("b", Duration.ofSeconds(1), () -> {}));
        // Two turns ahead: done after 300 ms, which would fit in 350 ms, but not with the margin.
        assertEquals(
                SessionTurnGate.Admission.WOULD_MISS_DEADLINE,
                gate.submit("c", Duration.ofMillis(350), () -> {}));
        // Without a deadline there is no estimate to refuse on.
        assertEquals(SessionTurnGate.Admission.ACCEPTED, gate.submit("d", () -> {}));
    }

    @Test
    void aTurnRunningLongerThanTheAverageRaisesTheEstimate() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        SessionTurnGate gate =
                new SessionTurnGate(pool, 1, 64, 10, Duration.ofMillis(100), ChannelMetrics.NONE);
        gate.submit(
                "slow",
                Duration.ofSeconds(10),
                () -> {
                    started.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        Thread.sleep(300);

        // On the 100 ms average, one turn to wait for, its own and one of margin fit in 350 ms. The
        // running turn has already taken 300 ms, which raises the estimate to 200 ms a turn (capped
        // at twice the average): 600 ms.
        assertEquals(
                SessionTurnGate.Admission.WOULD_MISS_DEADLINE,
                gate.submit("next", Duration.ofMillis(350), () -> {}));
        release.countDown();
    }

    @Test
    void aFollowUpRunsRightAfterItsSessionInsteadOfQueueingBehindOthers() throws Exception {
        ExecutorService single = Executors.newSingleThreadExecutor();
        try {
            SessionTurnGate gate = new SessionTurnGate(single, 64, 10);
            CountDownLatch release = new CountDownLatch(1);
            List<String> order = new CopyOnWriteArrayList<>();
            CountDownLatch done = new CountDownLatch(3);

            gate.submit("a", () -> {
                await(release);
                order.add("a1");
                done.countDown();
            });
            gate.submit("b", () -> {
                order.add("b1");
                done.countDown();
            });
            gate.submit("a", () -> {
                order.add("a2");
                done.countDown();
            });
            release.countDown();

            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("a1", "a2", "b1"), order);
            waitUntilIdle(gate);
        } finally {
            single.shutdownNow();
        }
    }

    @Test
    void aFollowUpOfARunningSessionOnlyWaitsForThatSession() throws Exception {
        ExecutorService single = Executors.newSingleThreadExecutor();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        try {
            SessionTurnGate gate =
                    new SessionTurnGate(single, 1, 64, 10, Duration.ofSeconds(1), ChannelMetrics.NONE);
            gate.submit("a", Duration.ofSeconds(10), () -> {
                started.countDown();
                await(release);
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            for (String other : List.of("b", "c", "d")) {
                gate.submit(other, Duration.ofSeconds(10), () -> {});
            }

            // Behind a's running turn only: 3 s with margin. A new session waits for the five
            // turns ahead of it on the one worker: 7 s.
            assertEquals(
                    SessionTurnGate.Admission.ACCEPTED,
                    gate.submit("a", Duration.ofMillis(3500), () -> {}));
            assertEquals(
                    SessionTurnGate.Admission.WOULD_MISS_DEADLINE,
                    gate.submit("e", Duration.ofMillis(3500), () -> {}));
        } finally {
            release.countDown();
            single.shutdownNow();
        }
    }

    @Test
    void aTurnWithAFreeWorkerIsAdmittedWhateverTheEstimate() {
        Queue<Runnable> scheduled = new ArrayDeque<>();
        SessionTurnGate gate =
                new SessionTurnGate(scheduled::add, 2, 64, 10, Duration.ofSeconds(10), ChannelMetrics.NONE);

        // A 10 s estimate against 1 s left: still admitted while a worker is free, so a wrong
        // estimate can never refuse everything with nothing running to correct it.
        assertEquals(SessionTurnGate.Admission.ACCEPTED, gate.submit("a", Duration.ofSeconds(1), () -> {}));
        assertEquals(SessionTurnGate.Admission.ACCEPTED, gate.submit("b", Duration.ofSeconds(1), () -> {}));
        assertEquals(
                SessionTurnGate.Admission.WOULD_MISS_DEADLINE,
                gate.submit("c", Duration.ofSeconds(1), () -> {}));
        // Nothing is admitted with no time left at all.
        SessionTurnGate idle =
                new SessionTurnGate(scheduled::add, 2, 64, 10, Duration.ofMillis(1), ChannelMetrics.NONE);
        assertEquals(SessionTurnGate.Admission.WOULD_MISS_DEADLINE, idle.submit("a", Duration.ZERO, () -> {}));
    }

    @Test
    void turnsThatEndedEarlyDoNotMoveTheEstimate() throws Exception {
        SessionTurnGate gate =
                new SessionTurnGate(pool, 4, 64, 10, Duration.ofMillis(100), ChannelMetrics.NONE);
        CountDownLatch done = new CountDownLatch(20);
        for (int i = 0; i < 20; i++) {
            SessionTurnGate.Turn expired = () -> {
                done.countDown();
                return false;
            };
            gate.submit("s" + i, Duration.ofSeconds(10), expired);
        }
        assertTrue(done.await(5, TimeUnit.SECONDS));
        waitUntilIdle(gate);
        assertEquals(Duration.ofMillis(100), gate.averageTurn());

        CountDownLatch measured = new CountDownLatch(1);
        SessionTurnGate.Turn ran = () -> {
            measured.countDown();
            return true;
        };
        gate.submit("real", Duration.ofSeconds(10), ran);
        assertTrue(measured.await(5, TimeUnit.SECONDS));
        waitUntilIdle(gate);
        assertTrue(gate.averageTurn().compareTo(Duration.ofMillis(100)) < 0, "a real turn does count");
    }

    @Test
    void aFailingTurnReleasesTheSession() throws Exception {
        SessionTurnGate gate = new SessionTurnGate(pool, 64, 10);
        CountDownLatch next = new CountDownLatch(1);

        gate.submit(
                "s",
                () -> {
                    throw new IllegalStateException("turn blew up");
                });
        gate.submit("s", next::countDown);

        assertTrue(next.await(5, TimeUnit.SECONDS));
        waitUntilIdle(gate);
    }

    private static void waitUntilIdle(SessionTurnGate gate) throws InterruptedException {
        for (int i = 0; i < 100 && gate.activeSessions() > 0; i++) {
            Thread.sleep(10);
        }
        assertEquals(0, gate.activeSessions());
        assertEquals(0, gate.pendingTurns());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
