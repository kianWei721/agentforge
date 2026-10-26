package com.agentforge.infrastructure.channel;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.channel.ChannelIntent;
import com.agentforge.channel.handoff.ChannelHandoff;
import com.agentforge.channel.handoff.HandoffOpened;
import com.agentforge.channel.handoff.HandoffOpening;
import com.agentforge.channel.handoff.HandoffResolution;
import com.agentforge.channel.handoff.HandoffStatus;
import com.agentforge.channel.handoff.NotifyStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcChannelHandoffRepositoryTest {

    static final Instant T0 = Instant.parse("2026-10-26T06:00:00Z");

    private JdbcChannelHandoffRepository repository;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:channel_handoff;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createTable(jdbc);
        repository = new JdbcChannelHandoffRepository(jdbc);
    }

    /** Same columns and keys as V13; H2 compares strings case-sensitively, like its binary columns. */
    static void createTable(JdbcTemplate jdbc) {
        jdbc.execute("DROP TABLE IF EXISTS af_channel_handoff");
        jdbc.execute(
                """
                CREATE TABLE af_channel_handoff (
                    id VARCHAR(64) PRIMARY KEY,
                    active_key VARCHAR(128),
                    channel VARCHAR(32) NOT NULL,
                    bot_id VARCHAR(128) NOT NULL,
                    chat_type VARCHAR(16) NOT NULL,
                    chat_id VARCHAR(128),
                    session_key VARCHAR(128) NOT NULL,
                    user_id VARCHAR(128) NOT NULL,
                    customer_id VARCHAR(128),
                    sender_userid VARCHAR(128) NOT NULL,
                    first_inbound_id VARCHAR(64) NOT NULL,
                    last_inbound_id VARCHAR(64) NOT NULL,
                    message_count INT NOT NULL,
                    intent VARCHAR(32) NOT NULL,
                    intent_source VARCHAR(32) NOT NULL,
                    intent_confidence DECIMAL(4, 3),
                    excerpt VARCHAR(255),
                    status VARCHAR(16) NOT NULL,
                    claimed_by VARCHAR(128),
                    claimed_at TIMESTAMP(6),
                    resolved_by VARCHAR(128),
                    resolved_at TIMESTAMP(6),
                    resolution VARCHAR(16),
                    resolution_note VARCHAR(512),
                    notify_status VARCHAR(16) NOT NULL,
                    notify_attempts INT NOT NULL,
                    next_notify_at TIMESTAMP(6),
                    notified_at TIMESTAMP(6),
                    notify_error VARCHAR(128),
                    last_message_at TIMESTAMP(6) NOT NULL,
                    created_at TIMESTAMP(6) NOT NULL,
                    updated_at TIMESTAMP(6) NOT NULL,
                    UNIQUE (active_key),
                    UNIQUE (first_inbound_id)
                )
                """);
    }

    @Test
    void aSessionHasOneOpenTicketThatTakesItsLaterMessages() {
        HandoffOpened first = repository.open(opening("in-1", "session-a", T0));
        HandoffOpened second = repository.open(opening("in-2", "session-a", T0.plusSeconds(30)));
        HandoffOpened other = repository.open(opening("in-3", "SESSION-A", T0.plusSeconds(40)));

        assertFalse(first.merged());
        assertEquals(new HandoffOpened(first.handoffId(), true), second);
        assertFalse(other.merged(), "session keys are case-sensitive");

        ChannelHandoff ticket = repository.find(first.handoffId()).orElseThrow();
        assertEquals(2, ticket.messageCount());
        assertEquals("in-1", ticket.firstInboundId());
        assertEquals("in-2", ticket.lastInboundId());
        assertEquals(T0.plusSeconds(30), ticket.lastMessageAt());
        assertEquals(T0, ticket.createdAt());
        assertEquals(HandoffStatus.PENDING, ticket.status());
        assertEquals(NotifyStatus.PENDING, ticket.notifyStatus());
        assertEquals("C001", ticket.customerId());
        assertEquals(0.875, ticket.intentConfidence());
        assertEquals("我要投诉", ticket.excerpt());
    }

    @Test
    void onceResolvedTheSessionGetsANewTicket() {
        String first = repository.open(opening("in-1", "session-a", T0)).handoffId();
        assertTrue(repository.resolve(first, "kefu-1", HandoffResolution.HANDLED, "已回电", T0.plusSeconds(60)));

        HandoffOpened next = repository.open(opening("in-2", "session-a", T0.plusSeconds(90)));

        assertFalse(next.merged());
        assertNotEquals(first, next.handoffId());
        ChannelHandoff resolved = repository.find(first).orElseThrow();
        assertEquals(HandoffStatus.RESOLVED, resolved.status());
        assertEquals("kefu-1", resolved.resolvedBy());
        assertEquals("kefu-1", resolved.claimedBy(), "resolving unclaimed counts as taking it");
        assertNull(resolved.claimedAt());
        assertEquals(HandoffResolution.HANDLED, resolved.resolution());
        assertEquals(NotifyStatus.CANCELLED, resolved.notifyStatus(), "nobody is told about a closed ticket");
    }

    @Test
    void claimingAndResolvingOnlyFromTheExpectedState() {
        String id = repository.open(opening("in-1", "session-a", T0)).handoffId();

        assertTrue(repository.claim(id, "kefu-1", T0.plusSeconds(10)));
        assertEquals(NotifyStatus.CANCELLED, repository.find(id).orElseThrow().notifyStatus(),
                "the staff group is not asked to claim a claimed ticket");
        assertTrue(repository.dueForNotification(T0.plusSeconds(10), 10).isEmpty());
        assertFalse(repository.claim(id, "kefu-2", T0.plusSeconds(11)), "already claimed");
        assertFalse(repository.resolve(id, "kefu-2", HandoffResolution.HANDLED, null, T0.plusSeconds(12)),
                "claimed by someone else");
        assertTrue(repository.resolve(id, "kefu-1", HandoffResolution.FALSE_TRIGGER, null, T0.plusSeconds(13)));
        assertFalse(repository.resolve(id, "kefu-1", HandoffResolution.HANDLED, null, T0.plusSeconds(14)),
                "already resolved");
        assertFalse(repository.claim("nope", "kefu-1", T0));

        ChannelHandoff ticket = repository.find(id).orElseThrow();
        assertEquals(T0.plusSeconds(10), ticket.claimedAt());
        assertEquals(HandoffResolution.FALSE_TRIGGER, ticket.resolution());
    }

    @Test
    void listingIsOldestFirstByStatus() {
        String a = repository.open(opening("in-1", "s-a", T0.plusSeconds(20))).handoffId();
        String b = repository.open(opening("in-2", "s-b", T0)).handoffId();
        String c = repository.open(opening("in-3", "s-c", T0.plusSeconds(40))).handoffId();
        repository.claim(c, "kefu-1", T0.plusSeconds(50));

        assertEquals(List.of(b, a), ids(repository.list(HandoffStatus.PENDING, null, 10)));
        assertEquals(List.of(a), ids(repository.list(HandoffStatus.PENDING, T0.plusSeconds(10), 10)));
        assertEquals(List.of(b), ids(repository.list(HandoffStatus.PENDING, null, 1)));
        assertEquals(Map.of(HandoffStatus.PENDING, 2L, HandoffStatus.CLAIMED, 1L), repository.countOpen());
    }

    @Test
    void theNoticeOutboxTracksAttemptsAndRetryTimes() {
        String a = repository.open(opening("in-1", "s-a", T0)).handoffId();
        String b = repository.open(opening("in-2", "s-b", T0.plusSeconds(1))).handoffId();
        HandoffOpening unnotified = opening("in-3", "s-c", T0.plusSeconds(2));
        String c = repository.open(new HandoffOpening(
                unnotified.channel(), unnotified.botId(), unnotified.chatType(), unnotified.chatId(),
                unnotified.sessionKey(), unnotified.userId(), unnotified.customerId(), unnotified.senderUserId(),
                unnotified.inboundId(), unnotified.intent(), unnotified.excerpt(), false, unnotified.at()))
                .handoffId();

        assertEquals(List.of(a, b), ids(repository.dueForNotification(T0.plusSeconds(5), 10)), "no webhook, no notice");
        assertEquals(NotifyStatus.DISABLED, repository.find(c).orElseThrow().notifyStatus());

        repository.markNotifyRetry(a, 1, T0.plusSeconds(15), "errcode=93000", T0.plusSeconds(5));
        repository.markNotified(List.of(b), T0.plusSeconds(5));
        assertEquals(List.of(), ids(repository.dueForNotification(T0.plusSeconds(10), 10)));
        assertEquals(List.of(a), ids(repository.dueForNotification(T0.plusSeconds(15), 10)));

        repository.markNotifyFailed(a, 5, "x".repeat(300), T0.plusSeconds(20));
        ChannelHandoff failed = repository.find(a).orElseThrow();
        assertEquals(NotifyStatus.FAILED, failed.notifyStatus());
        assertEquals(5, failed.notifyAttempts());
        assertEquals(128, failed.notifyError().length());
        ChannelHandoff sent = repository.find(b).orElseThrow();
        assertEquals(NotifyStatus.SENT, sent.notifyStatus());
        assertEquals(T0.plusSeconds(5), sent.notifiedAt());

        repository.markNotified(List.of(a), T0.plusSeconds(30));
        assertEquals(NotifyStatus.FAILED, repository.find(a).orElseThrow().notifyStatus(), "only from PENDING");
    }

    static HandoffOpening opening(String inboundId, String sessionKey, Instant at) {
        return new HandoffOpening(
                "wecom", "bot-1", "GROUP", "wrkCustA", sessionKey, "wecom-group:acme", "C001", "zhangsan",
                inboundId, new ChannelIntent("handoff", "MODEL", 0.8749, "gw-1"), "我要投诉", true, at);
    }

    private static List<String> ids(List<ChannelHandoff> tickets) {
        return tickets.stream().map(ChannelHandoff::id).toList();
    }
}
