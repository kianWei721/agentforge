package com.agentforge.channel;

import java.time.Duration;

/**
 * What a channel reports about admission and turns. Plain Java so the channel module does not
 * depend on a metrics library; the application binds it to Micrometer.
 */
public interface ChannelMetrics {

    ChannelMetrics NONE = new ChannelMetrics() {};

    /** One per inbound message: ACCEPTED, SESSION_BUSY, OVERLOADED, WOULD_MISS_DEADLINE, or a refusal code. */
    default void admission(String outcome) {}

    /** From admission to the turn getting a worker. */
    default void queueWait(Duration wait) {}

    /** How long a worker was busy with the turn. */
    default void turnDuration(Duration duration) {}

    /** DONE, or the failure code (TURN_TIMEOUT, EXPIRED_IN_QUEUE, MODEL_..., ...). */
    default void turnOutcome(String outcome) {}
}
