package com.agentforge.channel.wecom;

import static org.junit.jupiter.api.Assertions.*;

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

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
