package com.agentforge.channel.wecom;

import com.agentforge.channel.ChannelMetrics;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Runs turns of the same session one after another, in arrival order, and turns of different
 * sessions in parallel on a fixed pool.
 *
 * <p>A turn appends the user message, runs the agent, and appends the answer. Two turns of one
 * session running at once would interleave messages and race on the session's agent state. A
 * session's turns run back to back on the worker that took its first one, so a follow-up never
 * queues behind everyone else a second time.
 *
 * <p>Admission is decided by deadline, not only by queue length: WeCom stops pulling a reply a
 * fixed time after the user's message, so a turn that could only finish after that is refused at
 * once rather than accepted and left to expire. Throughput is workers divided by turn duration;
 * the wait for a new turn is estimated from a moving average of how long turns actually take.
 *
 * <p>In-JVM only: with more than one instance, the same session needs a shared lock or
 * session-affine routing.
 */
public final class SessionTurnGate {

    public enum Admission {
        ACCEPTED,
        /** This session already has the maximum number of turns waiting. */
        SESSION_BUSY,
        /** Too many turns waiting across all sessions: the memory bound. */
        OVERLOADED,
        /** It would only finish after the reply window closes, so it is refused now. */
        WOULD_MISS_DEADLINE
    }

    /** One turn. */
    @FunctionalInterface
    public interface Turn {
        /**
         * @return whether it ran the agent to an answer or to its time limit; only those say how
         *     long a turn takes. A turn that ended early (expired in the queue, refused, failed
         *     fast) must not pull the estimate down.
         */
        boolean run();
    }

    private static final double EWMA_WEIGHT = 0.2;

    private final Executor executor;
    private final int workers;
    private final int maxPending;
    private final int maxQueuedPerSession;
    private final ChannelMetrics metrics;

    /** Present while a turn of the session runs or is scheduled. */
    private final Map<String, SessionState> sessions = new HashMap<>();

    /** Turns admitted but not yet started, across all sessions. */
    private int pending;

    /**
     * Sessions handed to the executor whose first turn has not started: the only waiting turns
     * that need a worker of their own. A session's follow-ups run on the worker it already holds.
     */
    private int scheduledHeads;

    /** Start times of the turns running now, keyed by turn. */
    private final Map<Object, Long> runningSince = new HashMap<>();

    private double averageTurnNanos;

    /** Without a deadline estimate: admission by queue length only. */
    public SessionTurnGate(Executor executor, int maxPending, int maxQueuedPerSession) {
        this(executor, Integer.MAX_VALUE, maxPending, maxQueuedPerSession, Duration.ZERO, ChannelMetrics.NONE);
    }

    /**
     * @param workers threads in {@code executor}; with the turn duration this sets throughput
     * @param initialTurnEstimate assumed turn duration until real ones have been measured
     */
    public SessionTurnGate(
            Executor executor,
            int workers,
            int maxPending,
            int maxQueuedPerSession,
            Duration initialTurnEstimate,
            ChannelMetrics metrics) {
        if (workers < 1) {
            throw new IllegalArgumentException("workers must be positive");
        }
        this.executor = executor;
        this.workers = workers;
        this.maxPending = maxPending;
        this.maxQueuedPerSession = maxQueuedPerSession;
        this.averageTurnNanos = initialTurnEstimate.toNanos();
        this.metrics = metrics;
    }

    public Admission submit(String sessionKey, Runnable turn) {
        return submit(sessionKey, null, turn);
    }

    /** A turn that always counts towards the estimate. */
    public Admission submit(String sessionKey, Duration timeLeft, Runnable turn) {
        return submit(
                sessionKey,
                timeLeft,
                () -> {
                    turn.run();
                    return true;
                });
    }

    /** @param timeLeft until the reply window closes; null admits without a deadline check */
    public Admission submit(String sessionKey, Duration timeLeft, Turn turn) {
        Queued queued = new Queued(turn, System.nanoTime());
        synchronized (this) {
            SessionState session = sessions.get(sessionKey);
            if (session != null && session.waiting.size() >= maxQueuedPerSession) {
                return Admission.SESSION_BUSY;
            }
            if (pending >= maxPending) {
                return Admission.OVERLOADED;
            }
            if (timeLeft != null && wouldMissDeadline(session, timeLeft)) {
                return Admission.WOULD_MISS_DEADLINE;
            }
            pending++;
            if (session != null) {
                session.waiting.addLast(queued);
                return Admission.ACCEPTED;
            }
            sessions.put(sessionKey, new SessionState());
            scheduledHeads++;
        }
        try {
            executor.execute(() -> drain(sessionKey, queued));
        } catch (RejectedExecutionException shuttingDown) {
            synchronized (this) {
                sessions.remove(sessionKey);
                pending--;
                scheduledHeads--;
            }
            return Admission.OVERLOADED;
        }
        return Admission.ACCEPTED;
    }

    /**
     * Whether a new turn would finish too late.
     *
     * <p>A turn that gets a free worker right away is always admitted: the channel cuts it to the
     * time left, and refusing it would leave nothing running to correct an estimate gone too high.
     *
     * <p>Otherwise the turn finishes after the turns ahead of it, its own, and one more as margin:
     * the estimate is an average, and the turns that miss the deadline are the ones closest to it.
     * A follow-up of a session that is already running only waits for that session's turns. Any
     * other turn first waits for a worker behind everything queued, then for its session's turns.
     */
    private boolean wouldMissDeadline(SessionState session, Duration timeLeft) {
        if (timeLeft.isNegative() || timeLeft.isZero()) {
            return true;
        }
        if (session == null && runningSince.size() + scheduledHeads < workers) {
            return false;
        }
        double turnsAhead;
        if (session != null && session.started) {
            turnsAhead = 1 + session.waiting.size();
        } else {
            long slotsAhead = pending + Math.max(0, runningSince.size() + 1 - workers);
            double rounds = workers == Integer.MAX_VALUE ? 0 : Math.ceil((double) slotsAhead / workers);
            turnsAhead = rounds + (session == null ? 0 : 1 + session.waiting.size());
        }
        return (turnsAhead + 2) * estimatedTurnNanos() > timeLeft.toNanos();
    }

    /**
     * A turn is assumed to take the moving average. The average only learns from finished turns
     * and lags a slowdown, so the turns running now count too: if they have on average already
     * taken longer, the estimate rises with them, but to at most twice the average. A stall of a
     * few hundred milliseconds, multiplied by a queue hundreds of turns deep, would otherwise refuse
     * thousands of messages that would have been answered in time. Before any turn has finished,
     * the configured initial estimate stands in for the average, so it should err on the slow side.
     */
    private double estimatedTurnNanos() {
        double turnNanos = averageTurnNanos;
        if (!runningSince.isEmpty()) {
            long now = System.nanoTime();
            double elapsed = 0;
            for (long since : runningSince.values()) {
                elapsed += now - since;
            }
            turnNanos = Math.max(turnNanos, Math.min(elapsed / runningSince.size(), 2 * averageTurnNanos));
        }
        return turnNanos;
    }

    public synchronized int pendingTurns() {
        return pending;
    }

    public synchronized int activeSessions() {
        return sessions.size();
    }

    public synchronized Duration averageTurn() {
        return Duration.ofNanos((long) averageTurnNanos);
    }

    /** Runs a session's turns back to back on this worker until none is waiting. */
    private void drain(String sessionKey, Queued first) {
        Queued current = first;
        try {
            while (current != null) {
                run(sessionKey, current);
                current = next(sessionKey);
            }
        } finally {
            if (current != null) {
                // Left by an Error: the rest of the session goes to another worker.
                Queued rest = next(sessionKey);
                if (rest != null) {
                    synchronized (this) {
                        sessions.get(sessionKey).started = false;
                        scheduledHeads++;
                    }
                    executor.execute(() -> drain(sessionKey, rest));
                }
            }
        }
    }

    private void run(String sessionKey, Queued queued) {
        Object id = new Object();
        long started = System.nanoTime();
        synchronized (this) {
            pending--;
            runningSince.put(id, started);
            SessionState session = sessions.get(sessionKey);
            if (!session.started) {
                session.started = true;
                scheduledHeads--;
            }
        }
        boolean measured = false;
        try {
            measured = queued.turn().run();
        } catch (RuntimeException ignored) {
            // A turn reports its own failure; it must never stall the session behind it.
        } finally {
            long took = System.nanoTime() - started;
            synchronized (this) {
                runningSince.remove(id);
                if (measured) {
                    averageTurnNanos =
                            averageTurnNanos == 0
                                    ? took
                                    : (1 - EWMA_WEIGHT) * averageTurnNanos + EWMA_WEIGHT * took;
                }
            }
            report(started - queued.admittedAt(), took);
        }
    }

    private synchronized Queued next(String sessionKey) {
        SessionState session = sessions.get(sessionKey);
        Queued next = session == null ? null : session.waiting.pollFirst();
        if (next == null) {
            sessions.remove(sessionKey);
        }
        return next;
    }

    private void report(long waitedNanos, long tookNanos) {
        try {
            metrics.queueWait(Duration.ofNanos(waitedNanos));
            metrics.turnDuration(Duration.ofNanos(tookNanos));
        } catch (RuntimeException ignored) {
            // Metrics are best effort; a session must not stall on them.
        }
    }

    private record Queued(Turn turn, long admittedAt) {}

    /** The turns waiting behind the one running or scheduled, and whether that one has started. */
    private static final class SessionState {
        private final Deque<Queued> waiting = new ArrayDeque<>();
        private boolean started;
    }
}
