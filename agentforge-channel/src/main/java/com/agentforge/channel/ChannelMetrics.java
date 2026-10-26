package com.agentforge.channel;

import com.agentforge.channel.handoff.HandoffStatus;
import java.time.Duration;
import java.util.Map;

/**
 * What a channel reports about admission and turns. Plain Java so the channel module does not
 * depend on a metrics library; the application binds it to Micrometer.
 */
public interface ChannelMetrics {

    ChannelMetrics NONE = new ChannelMetrics() {};

    /**
     * One per inbound message: ACCEPTED, SESSION_BUSY, OVERLOADED, WOULD_MISS_DEADLINE, a refusal
     * code, or HANDOFF and INTENT_REFUSED when a phrase decided on arrival.
     */
    default void admission(String outcome) {}

    /** From admission to the turn getting a worker. */
    default void queueWait(Duration wait) {}

    /** How long a worker was busy with the turn. */
    default void turnDuration(Duration duration) {}

    /** DONE, HANDOFF, or the failure code (TURN_TIMEOUT, EXPIRED_IN_QUEUE, INTENT_REFUSED, MODEL_...). */
    default void turnOutcome(String outcome) {}

    /**
     * One per routed message: the intent and how it was decided (RULE, MODEL, FALLBACK_...). The
     * one place every hand-off is counted, whether a phrase or the classifier decided it.
     */
    default void intent(String intent, String source) {}

    /** A message handed to a person: a new ticket, or merged into the session's open one. */
    default void handoffOpened(boolean merged, String source) {}

    /** One per notice attempt: SENT, RETRY, FAILED or RATE_LIMITED. */
    default void handoffNotify(String outcome) {}

    /** From a ticket opening to its notice being accepted. */
    default void handoffNotifyDelay(Duration delay) {}

    /** Open tickets by status, as last counted. */
    default void openHandoffs(Map<HandoffStatus, Long> counts) {}

    /** From a ticket opening to a staff member claiming it. */
    default void handoffClaimWait(Duration wait) {}

    /** From a ticket opening to it being resolved, and how. */
    default void handoffResolved(String resolution, Duration open) {}
}
