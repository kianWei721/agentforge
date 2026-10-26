package com.agentforge.infrastructure.channel;

import com.agentforge.channel.handoff.HandoffNotificationDispatcher;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * One daemon thread ticking the hand-off notices; nothing runs without a webhook. A fixed delay,
 * not a fixed rate: a slow webhook stretches the interval rather than bunching messages after it.
 */
public final class HandoffNotificationLoop implements AutoCloseable {

    private final ScheduledExecutorService scheduler;

    private HandoffNotificationLoop(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    public static HandoffNotificationLoop off() {
        return new HandoffNotificationLoop(null);
    }

    public static HandoffNotificationLoop start(HandoffNotificationDispatcher dispatcher, Duration interval) {
        ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "wecom-handoff-notice");
                            thread.setDaemon(true);
                            return thread;
                        });
        scheduler.scheduleWithFixedDelay(
                dispatcher::tick, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        return new HandoffNotificationLoop(scheduler);
    }

    public boolean running() {
        return scheduler != null && !scheduler.isShutdown();
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
