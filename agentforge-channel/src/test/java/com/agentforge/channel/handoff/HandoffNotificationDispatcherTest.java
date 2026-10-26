package com.agentforge.channel.handoff;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.channel.ChannelMetrics;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HandoffNotificationDispatcherTest {

    private static final Instant T0 = Instant.parse("2026-10-26T06:03:00Z");

    private final SettableClock clock = new SettableClock(T0);
    private final Tickets tickets = new Tickets();
    private final List<String> sent = new ArrayList<>();
    private final List<String> outcomes = new ArrayList<>();
    private HandoffNotificationSender.Result next = HandoffNotificationSender.Result.SENT;

    private final HandoffNotificationDispatcher dispatcher =
            new HandoffNotificationDispatcher(
                    tickets,
                    text -> {
                        sent.add(text);
                        return next;
                    },
                    clock,
                    new ChannelMetrics() {
                        @Override
                        public void handoffNotify(String outcome) {
                            outcomes.add(outcome);
                        }
                    },
                    10,
                    5,
                    ZoneId.of("Asia/Shanghai"));

    @Test
    void oneTicketIsOneMessageAndThenMarkedSent() {
        tickets.add(ticket("t1", "GROUP", "wecom-group:acme", "C001", "RULE", null, "我要投诉你们的会计"));

        dispatcher.tick();

        assertEquals(1, sent.size());
        String text = sent.get(0);
        assertTrue(text.startsWith("【转人工】待认领 1 条"), text);
        assertTrue(text.contains("1) 群 acme（客户 C001）｜发起人 zhangsan｜短语｜10-26 14:03"), text);
        assertTrue(text.contains("\"我要投诉你们的会计\""), text);
        assertTrue(text.contains("工单 t1"), text);
        assertEquals(NotifyStatus.SENT, tickets.get("t1").notifyStatus());

        dispatcher.tick();
        assertEquals(1, sent.size(), "nothing left to send");
    }

    @Test
    void aBurstIsOneMessageThatListsTenAndCountsTheRest() {
        for (int i = 0; i < 25; i++) {
            tickets.add(ticket("t" + i, "SINGLE", "u-" + i, null, "MODEL", 0.92, "第 " + i + " 条"));
        }

        dispatcher.tick();

        assertEquals(1, sent.size(), "one message per tick, however many tickets");
        String text = sent.get(0);
        assertTrue(text.contains("待认领 25 条"), text);
        assertTrue(text.contains("10) 单聊 u-9"), text);
        assertFalse(text.contains("11) "), text);
        assertTrue(text.contains("另有 15 条"), text);
        assertTrue(text.contains("模型 0.92"), text);
        assertTrue(tickets.all().stream().allMatch(t -> t.notifyStatus() == NotifyStatus.SENT), "counted ones too");
    }

    @Test
    void theMessageStaysWithinWeComsTextLimit() {
        String longExcerpt = "很长的中文内容".repeat(30);
        for (int i = 0; i < 10; i++) {
            tickets.add(ticket("t" + i, "GROUP", "wecom-group:g" + i, "C" + i, "RULE", null, longExcerpt));
        }

        dispatcher.tick();

        String text = sent.get(0);
        assertTrue(text.getBytes(StandardCharsets.UTF_8).length <= 2048, "bytes: " + text.getBytes(StandardCharsets.UTF_8).length);
        assertTrue(text.contains("另有"), "what did not fit is counted");
    }

    @Test
    void aFailureIsRetriedWithBackoffUntilItGivesUp() {
        tickets.add(ticket("t1", "GROUP", "wecom-group:acme", "C001", "RULE", null, "转人工"));
        next = HandoffNotificationSender.Result.failed("errcode=93000");

        dispatcher.tick();
        assertEquals(1, tickets.get("t1").notifyAttempts());
        assertEquals(T0.plusSeconds(10), tickets.get("t1").nextNotifyAt());
        assertEquals("errcode=93000", tickets.get("t1").notifyError());

        dispatcher.tick();
        assertEquals(1, sent.size(), "not before its retry time");

        for (int attempt = 2; attempt <= 5; attempt++) {
            clock.set(tickets.get("t1").nextNotifyAt());
            dispatcher.tick();
        }
        assertEquals(5, sent.size());
        assertEquals(NotifyStatus.FAILED, tickets.get("t1").notifyStatus());
        assertEquals(List.of("RETRY", "RETRY", "RETRY", "RETRY", "FAILED"), outcomes);
        assertEquals(HandoffStatus.PENDING, tickets.get("t1").status(), "the ticket itself is still open");
    }

    @Test
    void rateLimitingPausesEverythingWithoutUsingAnAttempt() {
        tickets.add(ticket("t1", "GROUP", "wecom-group:acme", "C001", "RULE", null, "转人工"));
        next = HandoffNotificationSender.Result.rateLimited("errcode=45009");

        dispatcher.tick();
        assertEquals(0, tickets.get("t1").notifyAttempts());

        clock.set(T0.plusSeconds(30));
        dispatcher.tick();
        assertEquals(1, sent.size(), "paused for a minute");

        next = HandoffNotificationSender.Result.SENT;
        clock.set(T0.plusSeconds(61));
        dispatcher.tick();
        assertEquals(NotifyStatus.SENT, tickets.get("t1").notifyStatus());
        assertEquals(List.of("RATE_LIMITED", "SENT"), outcomes);
    }

    @Test
    void aBrokenRepositoryNeverStopsTheSchedule() {
        HandoffNotificationDispatcher broken =
                new HandoffNotificationDispatcher(
                        new Tickets() {
                            @Override
                            public Map<HandoffStatus, Long> countOpen() {
                                throw new IllegalStateException("database down");
                            }
                        },
                        text -> fail("nothing to send"),
                        clock,
                        ChannelMetrics.NONE,
                        10,
                        5,
                        ZoneId.of("Asia/Shanghai"));

        assertDoesNotThrow(broken::tick);
    }

    private ChannelHandoff ticket(
            String id, String chatType, String userId, String customerId, String source, Double confidence, String excerpt) {
        return new ChannelHandoff(
                id, "wecom", "bot-1", chatType, "chat", "session-" + id, userId, customerId, "zhangsan",
                "in-" + id, "in-" + id, 1, "handoff", source, confidence, excerpt, HandoffStatus.PENDING,
                null, null, null, null, null, null, NotifyStatus.PENDING, 0, null, null, null,
                T0, T0, T0);
    }

    /** The repository's notice bookkeeping, in memory. */
    static class Tickets implements ChannelHandoffRepository {
        private final Map<String, ChannelHandoff> rows = new LinkedHashMap<>();

        void add(ChannelHandoff ticket) {
            rows.put(ticket.id(), ticket);
        }

        ChannelHandoff get(String id) {
            return rows.get(id);
        }

        List<ChannelHandoff> all() {
            return List.copyOf(rows.values());
        }

        @Override
        public HandoffOpened open(HandoffOpening opening) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ChannelHandoff> find(String id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public List<ChannelHandoff> list(HandoffStatus status, Instant createdAfter, int limit) {
            return rows.values().stream().filter(t -> t.status() == status).limit(limit).toList();
        }

        @Override
        public boolean claim(String id, String staffUserId, Instant at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean resolve(String id, String staffUserId, HandoffResolution resolution, String note, Instant at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ChannelHandoff> dueForNotification(Instant now, int limit) {
            return rows.values().stream()
                    .filter(t -> t.notifyStatus() == NotifyStatus.PENDING)
                    .filter(t -> t.nextNotifyAt() == null || !t.nextNotifyAt().isAfter(now))
                    .limit(limit)
                    .toList();
        }

        @Override
        public void markNotified(List<String> ids, Instant at) {
            ids.forEach(id -> rows.put(id, notify(rows.get(id), NotifyStatus.SENT, rows.get(id).notifyAttempts(), null, null)));
        }

        @Override
        public void markNotifyRetry(String id, int attempts, Instant nextAt, String error, Instant at) {
            rows.put(id, notify(rows.get(id), NotifyStatus.PENDING, attempts, nextAt, error));
        }

        @Override
        public void markNotifyFailed(String id, int attempts, String error, Instant at) {
            rows.put(id, notify(rows.get(id), NotifyStatus.FAILED, attempts, null, error));
        }

        @Override
        public Map<HandoffStatus, Long> countOpen() {
            return Map.of(HandoffStatus.PENDING, (long) rows.size(), HandoffStatus.CLAIMED, 0L);
        }

        private static ChannelHandoff notify(
                ChannelHandoff t, NotifyStatus status, int attempts, Instant nextAt, String error) {
            return new ChannelHandoff(
                    t.id(), t.channel(), t.botId(), t.chatType(), t.chatId(), t.sessionKey(), t.userId(),
                    t.customerId(), t.senderUserId(), t.firstInboundId(), t.lastInboundId(), t.messageCount(),
                    t.intent(), t.intentSource(), t.intentConfidence(), t.excerpt(), t.status(), t.claimedBy(),
                    t.claimedAt(), t.resolvedBy(), t.resolvedAt(), t.resolution(), t.resolutionNote(), status,
                    attempts, nextAt, status == NotifyStatus.SENT ? Instant.EPOCH : null, error,
                    t.lastMessageAt(), t.createdAt(), t.updatedAt());
        }
    }
}
