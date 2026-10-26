package com.agentforge.channel.handoff;

import com.agentforge.channel.ChannelMetrics;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends the staff-group notices, one tick at a time, from the tickets that are their outbox.
 *
 * <p>One message per tick at most, listing up to {@code maxItems} tickets and counting the rest; every
 * ticket the tick picked up is then marked sent, listed or counted. The tick interval is therefore
 * the rate limit: at 5 seconds, 12 messages a minute under WeCom's 20 per robot, however many
 * groups hand off at once.
 *
 * <p>At least once: a crash after the webhook accepted a message and before the tickets are marked
 * sends it again on restart. Every ticket carries its id, so staff can tell.
 */
public final class HandoffNotificationDispatcher {

    /** Picked up per tick; listed or counted in one message. */
    static final int BATCH = 200;
    /** WeCom text messages take 2048 bytes; the rest is margin. */
    static final int MAX_BYTES = 2000;
    static final Duration RATE_LIMIT_PAUSE = Duration.ofSeconds(60);
    static final List<Duration> BACKOFF =
            List.of(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(2),
                    Duration.ofMinutes(5), Duration.ofMinutes(10));

    private static final System.Logger LOG = System.getLogger(HandoffNotificationDispatcher.class.getName());

    private final ChannelHandoffRepository repository;
    private final HandoffNotificationSender sender;
    private final Clock clock;
    private final ChannelMetrics metrics;
    private final int maxItems;
    private final int maxAttempts;
    private final DateTimeFormatter time;
    private Instant pausedUntil = Instant.MIN;

    public HandoffNotificationDispatcher(
            ChannelHandoffRepository repository,
            HandoffNotificationSender sender,
            Clock clock,
            ChannelMetrics metrics,
            int maxItems,
            int maxAttempts,
            ZoneId zone) {
        if (maxItems < 1 || maxAttempts < 1) {
            throw new IllegalArgumentException("max items and max attempts must be positive");
        }
        this.repository = repository;
        this.sender = sender;
        this.clock = clock;
        this.metrics = metrics;
        this.maxItems = maxItems;
        this.maxAttempts = maxAttempts;
        this.time = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(zone);
    }

    /** One scheduler thread calls this; it never throws, so the schedule is never cancelled. */
    public void tick() {
        try {
            metrics.openHandoffs(repository.countOpen());
            Instant now = clock.instant();
            if (now.isBefore(pausedUntil)) {
                return;
            }
            List<ChannelHandoff> due = repository.dueForNotification(now, BATCH);
            if (due.isEmpty()) {
                return;
            }
            HandoffNotificationSender.Result result = sender.send(message(due));
            if (result.sent()) {
                repository.markNotified(due.stream().map(ChannelHandoff::id).toList(), now);
                metrics.handoffNotify("SENT");
                due.forEach(ticket -> metrics.handoffNotifyDelay(Duration.between(ticket.createdAt(), now)));
            } else if (result.rateLimited()) {
                // Not the notice's fault: no attempt is used up, everything waits.
                pausedUntil = now.plus(RATE_LIMIT_PAUSE);
                metrics.handoffNotify("RATE_LIMITED");
                LOG.log(System.Logger.Level.WARNING, "Hand-off notice rate limited (" + result.error() + "), pausing");
            } else {
                retryOrGiveUp(due, result.error(), now);
            }
        } catch (RuntimeException exception) {
            LOG.log(System.Logger.Level.WARNING, "Hand-off notice tick failed", exception);
        }
    }

    private void retryOrGiveUp(List<ChannelHandoff> due, String error, Instant now) {
        boolean gaveUp = false;
        for (ChannelHandoff ticket : due) {
            int attempts = ticket.notifyAttempts() + 1;
            if (attempts >= maxAttempts) {
                repository.markNotifyFailed(ticket.id(), attempts, error, now);
                gaveUp = true;
            } else {
                Duration wait = BACKOFF.get(Math.min(attempts, BACKOFF.size()) - 1);
                repository.markNotifyRetry(ticket.id(), attempts, now.plus(wait), error, now);
            }
        }
        metrics.handoffNotify(gaveUp ? "FAILED" : "RETRY");
        LOG.log(System.Logger.Level.WARNING, "Hand-off notice not sent (" + error + ")");
    }

    String message(List<ChannelHandoff> due) {
        String header = "【转人工】待认领 " + due.size() + " 条";
        String footer = "认领：POST /api/v1/channel-handoffs/{id}/claim；结单：POST /api/v1/channel-handoffs/{id}/resolve";
        List<String> items = new ArrayList<>();
        int bytes = bytes(header) + bytes(footer) + 64;
        for (ChannelHandoff ticket : due) {
            if (items.size() == maxItems) {
                break;
            }
            String item = item(items.size() + 1, ticket);
            if (bytes + bytes(item) > MAX_BYTES) {
                break;
            }
            items.add(item);
            bytes += bytes(item);
        }
        StringBuilder text = new StringBuilder(header);
        items.forEach(item -> text.append('\n').append(item));
        if (items.size() < due.size()) {
            text.append("\n另有 ").append(due.size() - items.size())
                    .append(" 条，见待办列表 GET /api/v1/channel-handoffs?status=PENDING");
        }
        return text.append('\n').append(footer).toString();
    }

    private String item(int number, ChannelHandoff ticket) {
        String where = "GROUP".equals(ticket.chatType())
                ? "群 " + ticket.userId().replaceFirst("^wecom-group:", "")
                : "单聊 " + ticket.userId();
        String customer = ticket.customerId() == null ? "" : "（客户 " + ticket.customerId() + "）";
        String source = "MODEL".equals(ticket.intentSource())
                ? "模型 " + (ticket.intentConfidence() == null ? "" : String.format("%.2f", ticket.intentConfidence()))
                : "短语";
        StringBuilder item = new StringBuilder()
                .append(number).append(") ").append(where).append(customer)
                .append("｜发起人 ").append(ticket.senderUserId())
                .append("｜").append(source.strip())
                .append("｜").append(time.format(ticket.createdAt()));
        if (ticket.messageCount() > 1) {
            item.append("｜共 ").append(ticket.messageCount()).append(" 条");
        }
        if (ticket.excerpt() != null) {
            item.append("\n   \"").append(ticket.excerpt()).append('"');
        }
        return item.append("\n   工单 ").append(ticket.id()).toString();
    }

    private static int bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length + 1;
    }
}
