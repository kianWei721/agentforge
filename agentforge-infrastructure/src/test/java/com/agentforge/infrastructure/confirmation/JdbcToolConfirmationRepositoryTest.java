package com.agentforge.infrastructure.confirmation;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.runtime.confirmation.ToolConfirmation;
import com.agentforge.runtime.confirmation.ToolConfirmationAccessDeniedException;
import com.agentforge.runtime.confirmation.ToolConfirmationStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcToolConfirmationRepositoryTest {

    private JdbcToolConfirmationRepository repository;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(
                "jdbc:h2:mem:confirmation;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS af_tool_confirmation");
        jdbc.execute(
                """
                CREATE TABLE af_tool_confirmation (
                    id VARCHAR(64) PRIMARY KEY,
                    active_key VARCHAR(64),
                    requested_execution_id VARCHAR(64) NOT NULL,
                    requested_tool_call_id VARCHAR(128) NOT NULL,
                    consumed_execution_id VARCHAR(64),
                    consumed_tool_call_id VARCHAR(128),
                    agent_id VARCHAR(64) NOT NULL,
                    user_id VARCHAR(128) NOT NULL,
                    session_id VARCHAR(64) NOT NULL,
                    tool_name VARCHAR(128) NOT NULL,
                    argument_fingerprint VARCHAR(64) NOT NULL,
                    summary VARCHAR(512) NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    expires_at TIMESTAMP(6) NOT NULL,
                    approved_at TIMESTAMP(6),
                    rejected_at TIMESTAMP(6),
                    consumed_at TIMESTAMP(6),
                    created_at TIMESTAMP(6) NOT NULL,
                    updated_at TIMESTAMP(6) NOT NULL,
                    UNIQUE (active_key)
                )
                """);
        repository = new JdbcToolConfirmationRepository(jdbc);
    }

    @Test
    void shouldReuseActiveThenConsumeExactlyOnce() {
        Instant now = Instant.parse("2026-08-29T07:00:00Z");
        ToolConfirmation first = draft("confirmation-1", "binding-1", now);

        ToolConfirmation created = repository.createOrGetActive(first, now);
        ToolConfirmation reused =
                repository.createOrGetActive(
                        draft("confirmation-2", "binding-1", now.plusSeconds(1)),
                        now.plusSeconds(1));

        assertEquals(created.id(), reused.id());
        assertThrows(
                ToolConfirmationAccessDeniedException.class,
                () -> repository.approve(created.id(), "other-user", now.plusSeconds(2)));

        ToolConfirmation approved =
                repository.approve(created.id(), "user-1", now.plusSeconds(2));
        assertEquals(ToolConfirmationStatus.APPROVED, approved.status());

        ToolConfirmation consumed =
                repository.consumeApproved(
                                "binding-1",
                                "execution-consume",
                                "call-consume",
                                now.plusSeconds(3))
                        .orElseThrow();
        assertEquals(ToolConfirmationStatus.CONSUMED, consumed.status());
        assertEquals("execution-consume", consumed.consumedExecutionId());
        assertEquals("call-consume", consumed.consumedToolCallId());
        assertNull(consumed.activeKey());

        assertTrue(
                repository.consumeApproved(
                                "binding-1",
                                "execution-again",
                                "call-again",
                                now.plusSeconds(4))
                        .isEmpty());

        ToolConfirmation next =
                repository.createOrGetActive(
                        draft("confirmation-3", "binding-1", now.plusSeconds(5)),
                        now.plusSeconds(5));
        assertEquals("confirmation-3", next.id());
        assertEquals(ToolConfirmationStatus.PENDING, next.status());
    }

    @Test
    void shouldExpireWithoutAllowingLateApproval() {
        Instant now = Instant.parse("2026-08-29T07:00:00Z");
        ToolConfirmation shortLived =
                new ToolConfirmation(
                        "confirmation-expire",
                        "binding-expire",
                        "execution-1",
                        "call-1",
                        null,
                        null,
                        "agent-1",
                        "user-1",
                        "session-1",
                        "write_tool",
                        "fingerprint-1",
                        "safe summary",
                        ToolConfirmationStatus.PENDING,
                        now.plusSeconds(5),
                        null,
                        null,
                        null,
                        now,
                        now);
        repository.createOrGetActive(shortLived, now);

        ToolConfirmation expired =
                repository.approve(
                        shortLived.id(),
                        "user-1",
                        now.plus(6, ChronoUnit.SECONDS));

        assertEquals(ToolConfirmationStatus.EXPIRED, expired.status());
        assertNull(expired.activeKey());
        assertTrue(
                repository.consumeApproved(
                                "binding-expire",
                                "execution-2",
                                "call-2",
                                now.plusSeconds(7))
                        .isEmpty());
    }

    private static ToolConfirmation draft(String id, String activeKey, Instant now) {
        return new ToolConfirmation(
                id,
                activeKey,
                "execution-request",
                "call-request",
                null,
                null,
                "agent-1",
                "user-1",
                "session-1",
                "write_tool",
                "fingerprint-1",
                "safe summary",
                ToolConfirmationStatus.PENDING,
                now.plusSeconds(300),
                null,
                null,
                null,
                now,
                now);
    }
}
