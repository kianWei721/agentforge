package com.agentforge.infrastructure.channel;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.channel.ChannelInbound;
import com.agentforge.channel.ChannelIntent;
import com.agentforge.channel.ChannelInboundStatus;
import java.time.Instant;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcChannelInboundRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");

    private JdbcChannelInboundRepository repository;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(
                "jdbc:h2:mem:channel_inbound;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS af_channel_inbound");
        // Same columns and unique key as V11 and V12; H2 compares strings case-sensitively, like the
        // utf8mb4_bin columns there.
        jdbc.execute(
                """
                CREATE TABLE af_channel_inbound (
                    id VARCHAR(64) PRIMARY KEY,
                    channel VARCHAR(32) NOT NULL,
                    bot_id VARCHAR(128) NOT NULL,
                    msg_id VARCHAR(128) NOT NULL,
                    chat_type VARCHAR(16) NOT NULL,
                    chat_id VARCHAR(128),
                    sender_userid VARCHAR(128) NOT NULL,
                    agent_id VARCHAR(64),
                    user_id VARCHAR(128),
                    session_key VARCHAR(128),
                    execution_id VARCHAR(64),
                    status VARCHAR(16) NOT NULL,
                    error_code VARCHAR(64),
                    created_at TIMESTAMP(6) NOT NULL,
                    updated_at TIMESTAMP(6) NOT NULL,
                    intent VARCHAR(32),
                    intent_source VARCHAR(32),
                    intent_confidence DECIMAL(4, 3),
                    intent_request_id VARCHAR(64),
                    UNIQUE (channel, bot_id, msg_id)
                )
                """);
        repository = new JdbcChannelInboundRepository(jdbc);
    }

    @Test
    void aMessageIsRecordedOnceAndRedeliveriesAreRecognised() {
        assertTrue(repository.insertIfAbsent(row("id-1", "msg-1")));
        assertFalse(repository.insertIfAbsent(row("id-2", "msg-1")));
        // Message ids are case-sensitive.
        assertTrue(repository.insertIfAbsent(row("id-3", "MSG-1")));

        assertEquals("id-1", repository.find("wecom", "bot-1", "msg-1").orElseThrow().id());
    }

    @Test
    void completionRecordsTheOutcomeAndKeepsAKnownExecution() {
        repository.insertIfAbsent(row("id-1", "msg-1"));

        repository.complete("id-1", ChannelInboundStatus.FAILED, "exec-1", "TURN_TIMEOUT", NOW.plusSeconds(5));
        repository.complete("id-1", ChannelInboundStatus.FAILED, null, "TURN_TIMEOUT", NOW.plusSeconds(6));

        ChannelInbound stored = repository.find("wecom", "bot-1", "msg-1").orElseThrow();
        assertEquals(ChannelInboundStatus.FAILED, stored.status());
        assertEquals("exec-1", stored.executionId());
        assertEquals("TURN_TIMEOUT", stored.errorCode());
        assertEquals(NOW.plusSeconds(6), stored.updatedAt());
    }

    @Test
    void anIntentIsStoredOnArrivalOrRecordedAfterClassification() {
        ChannelInbound handoff =
                new ChannelInbound(
                        "id-1", "wecom", "bot-1", "msg-1", "GROUP", "chat-1", "zhangsan", null,
                        "wecom-group:a", "wecom:abc", null, ChannelInboundStatus.HANDOFF, null, NOW, NOW,
                        new ChannelIntent("handoff", "RULE", null, null));
        repository.insertIfAbsent(handoff);
        repository.insertIfAbsent(row("id-2", "msg-2"));

        repository.recordIntent("id-2", new ChannelIntent("answer", "MODEL", 0.87654, "gw-1"));

        ChannelInbound first = repository.find("wecom", "bot-1", "msg-1").orElseThrow();
        assertEquals(ChannelInboundStatus.HANDOFF, first.status());
        assertEquals(new ChannelIntent("handoff", "RULE", null, null), first.intent());
        assertEquals(
                new ChannelIntent("answer", "MODEL", 0.877, "gw-1"),
                repository.find("wecom", "bot-1", "msg-2").orElseThrow().intent());
    }

    @Test
    void aMessageRoutedWithoutIntentsHasNone() {
        repository.insertIfAbsent(row("id-1", "msg-1"));

        assertNull(repository.find("wecom", "bot-1", "msg-1").orElseThrow().intent());
    }

    private static ChannelInbound row(String id, String msgId) {
        return new ChannelInbound(
                id, "wecom", "bot-1", msgId, "SINGLE", null, "zhangsan", "agent-1", "dev-staff",
                "wecom:abc", null, ChannelInboundStatus.RECEIVED, null, NOW, NOW);
    }
}
