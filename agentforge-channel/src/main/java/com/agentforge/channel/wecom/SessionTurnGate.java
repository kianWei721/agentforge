package com.agentforge.channel.wecom;

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
 * session running at once would interleave messages and race on the session's agent state.
 *
 * <p>In-JVM only: with more than one instance, the same session needs a shared lock or
 * session-affine routing.
 */
public final class SessionTurnGate {

    public enum Admission {
        ACCEPTED,
        /** This session already has the maximum number of turns waiting. */
        SESSION_BUSY,
        /** Too many turns waiting across all sessions. */
        OVERLOADED
    }

    private final Executor executor;
    private final int maxPending;
    private final int maxQueuedPerSession;

    /** Present while a turn of the session runs or is scheduled; holds the turns after it. */
    private final Map<String, Deque<Runnable>> sessions = new HashMap<>();

    /** Turns admitted but not yet started, across all sessions. */
    private int pending;

    public SessionTurnGate(Executor executor, int maxPending, int maxQueuedPerSession) {
        this.executor = executor;
        this.maxPending = maxPending;
        this.maxQueuedPerSession = maxQueuedPerSession;
    }

    public Admission submit(String sessionKey, Runnable turn) {
        synchronized (this) {
            if (pending >= maxPending) {
                return Admission.OVERLOADED;
            }
            Deque<Runnable> waiting = sessions.get(sessionKey);
            if (waiting != null) {
                if (waiting.size() >= maxQueuedPerSession) {
                    return Admission.SESSION_BUSY;
                }
                waiting.addLast(turn);
                pending++;
                return Admission.ACCEPTED;
            }
            sessions.put(sessionKey, new ArrayDeque<>());
            pending++;
        }
        try {
            executor.execute(wrap(sessionKey, turn));
        } catch (RejectedExecutionException shuttingDown) {
            synchronized (this) {
                sessions.remove(sessionKey);
                pending--;
            }
            return Admission.OVERLOADED;
        }
        return Admission.ACCEPTED;
    }

    public synchronized int pendingTurns() {
        return pending;
    }

    public synchronized int activeSessions() {
        return sessions.size();
    }

    private Runnable wrap(String sessionKey, Runnable turn) {
        return () -> {
            synchronized (this) {
                pending--;
            }
            try {
                turn.run();
            } catch (RuntimeException ignored) {
                // A turn reports its own failure; it must never stall the session behind it.
            } finally {
                Runnable next;
                synchronized (this) {
                    Deque<Runnable> waiting = sessions.get(sessionKey);
                    next = waiting == null ? null : waiting.pollFirst();
                    if (next == null) {
                        sessions.remove(sessionKey);
                    }
                }
                if (next != null) {
                    executor.execute(wrap(sessionKey, next));
                }
            }
        };
    }
}
