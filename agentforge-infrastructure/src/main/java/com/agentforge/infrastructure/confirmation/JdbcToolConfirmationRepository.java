package com.agentforge.infrastructure.confirmation;

import com.agentforge.runtime.confirmation.ToolConfirmation;
import com.agentforge.runtime.confirmation.ToolConfirmationAccessDeniedException;
import com.agentforge.runtime.confirmation.ToolConfirmationInvalidStateException;
import com.agentforge.runtime.confirmation.ToolConfirmationNotFoundException;
import com.agentforge.runtime.confirmation.ToolConfirmationRepository;
import com.agentforge.runtime.confirmation.ToolConfirmationStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

public class JdbcToolConfirmationRepository implements ToolConfirmationRepository {

    private static final String COLUMNS =
            "id, active_key, requested_execution_id, requested_tool_call_id, "
                    + "consumed_execution_id, consumed_tool_call_id, agent_id, user_id, session_id, "
                    + "tool_name, argument_fingerprint, summary, status, expires_at, approved_at, "
                    + "rejected_at, consumed_at, created_at, updated_at";

    private static final RowMapper<ToolConfirmation> ROW_MAPPER =
            (rs, rowNum) -> map(rs);

    private final JdbcTemplate jdbcTemplate;

    public JdbcToolConfirmationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public ToolConfirmation createOrGetActive(ToolConfirmation draft, Instant now) {
        expireByActiveKey(draft.activeKey(), now);

        Optional<ToolConfirmation> existing = findActiveByKeyForUpdate(draft.activeKey());
        if (existing.isPresent()) {
            return existing.get();
        }

        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO af_tool_confirmation (
                        id, active_key, requested_execution_id, requested_tool_call_id,
                        consumed_execution_id, consumed_tool_call_id, agent_id, user_id,
                        session_id, tool_name, argument_fingerprint, summary, status,
                        expires_at, approved_at, rejected_at, consumed_at, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, NULL, NULL, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, ?, ?)
                    """,
                    draft.id(),
                    draft.activeKey(),
                    draft.requestedExecutionId(),
                    draft.requestedToolCallId(),
                    draft.agentId(),
                    draft.userId(),
                    draft.sessionId(),
                    draft.toolName(),
                    draft.argumentFingerprint(),
                    draft.summary(),
                    draft.status().name(),
                    Timestamp.from(draft.expiresAt()),
                    Timestamp.from(draft.createdAt()),
                    Timestamp.from(draft.updatedAt()));
            return draft;
        } catch (DuplicateKeyException duplicate) {
            return findActiveByKeyForUpdate(draft.activeKey())
                    .orElseThrow(() -> duplicate);
        }
    }

    @Override
    public Optional<ToolConfirmation> findById(String confirmationId) {
        List<ToolConfirmation> rows =
                jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM af_tool_confirmation WHERE id = ?",
                        ROW_MAPPER,
                        confirmationId);
        return rows.stream().findFirst();
    }

    @Override
    @Transactional
    public ToolConfirmation approve(String confirmationId, String userId, Instant now) {
        ToolConfirmation current = getForUpdate(confirmationId);
        verifyUser(current, userId);

        if (current.status().active() && !current.expiresAt().isAfter(now)) {
            expire(confirmationId, now);
            return getForUpdate(confirmationId);
        }
        if (current.status() == ToolConfirmationStatus.APPROVED) {
            return current;
        }
        if (current.status() != ToolConfirmationStatus.PENDING) {
            throw invalidTransition(current, "approve");
        }

        jdbcTemplate.update(
                """
                UPDATE af_tool_confirmation
                SET status = 'APPROVED', approved_at = ?, updated_at = ?
                WHERE id = ? AND status = 'PENDING'
                """,
                Timestamp.from(now),
                Timestamp.from(now),
                confirmationId);
        return getForUpdate(confirmationId);
    }

    @Override
    @Transactional
    public ToolConfirmation reject(String confirmationId, String userId, Instant now) {
        ToolConfirmation current = getForUpdate(confirmationId);
        verifyUser(current, userId);

        if (current.status().active() && !current.expiresAt().isAfter(now)) {
            expire(confirmationId, now);
            return getForUpdate(confirmationId);
        }
        if (current.status() == ToolConfirmationStatus.REJECTED) {
            return current;
        }
        if (current.status() != ToolConfirmationStatus.PENDING
                && current.status() != ToolConfirmationStatus.APPROVED) {
            throw invalidTransition(current, "reject");
        }

        jdbcTemplate.update(
                """
                UPDATE af_tool_confirmation
                SET status = 'REJECTED', active_key = NULL, rejected_at = ?, updated_at = ?
                WHERE id = ? AND status IN ('PENDING', 'APPROVED')
                """,
                Timestamp.from(now),
                Timestamp.from(now),
                confirmationId);
        return getForUpdate(confirmationId);
    }

    @Override
    @Transactional
    public Optional<ToolConfirmation> consumeApproved(
            String activeKey,
            String consumedExecutionId,
            String consumedToolCallId,
            Instant now) {
        expireByActiveKey(activeKey, now);
        Optional<ToolConfirmation> active = findActiveByKeyForUpdate(activeKey);
        if (active.isEmpty() || active.get().status() != ToolConfirmationStatus.APPROVED) {
            return Optional.empty();
        }

        ToolConfirmation current = active.get();
        int updated =
                jdbcTemplate.update(
                        """
                        UPDATE af_tool_confirmation
                        SET status = 'CONSUMED',
                            active_key = NULL,
                            consumed_execution_id = ?,
                            consumed_tool_call_id = ?,
                            consumed_at = ?,
                            updated_at = ?
                        WHERE id = ? AND status = 'APPROVED' AND expires_at > ?
                        """,
                        consumedExecutionId,
                        consumedToolCallId,
                        Timestamp.from(now),
                        Timestamp.from(now),
                        current.id(),
                        Timestamp.from(now));
        if (updated != 1) {
            return Optional.empty();
        }
        return findById(current.id());
    }

    private Optional<ToolConfirmation> findActiveByKeyForUpdate(String activeKey) {
        List<ToolConfirmation> rows =
                jdbcTemplate.query(
                        "SELECT "
                                + COLUMNS
                                + " FROM af_tool_confirmation WHERE active_key = ? FOR UPDATE",
                        ROW_MAPPER,
                        activeKey);
        return rows.stream().findFirst();
    }

    private ToolConfirmation getForUpdate(String confirmationId) {
        List<ToolConfirmation> rows =
                jdbcTemplate.query(
                        "SELECT "
                                + COLUMNS
                                + " FROM af_tool_confirmation WHERE id = ? FOR UPDATE",
                        ROW_MAPPER,
                        confirmationId);
        if (rows.isEmpty()) {
            throw new ToolConfirmationNotFoundException(confirmationId);
        }
        return rows.get(0);
    }

    private void expireByActiveKey(String activeKey, Instant now) {
        jdbcTemplate.update(
                """
                UPDATE af_tool_confirmation
                SET status = 'EXPIRED', active_key = NULL, updated_at = ?
                WHERE active_key = ?
                  AND status IN ('PENDING', 'APPROVED')
                  AND expires_at <= ?
                """,
                Timestamp.from(now),
                activeKey,
                Timestamp.from(now));
    }

    private void expire(String confirmationId, Instant now) {
        jdbcTemplate.update(
                """
                UPDATE af_tool_confirmation
                SET status = 'EXPIRED', active_key = NULL, updated_at = ?
                WHERE id = ? AND status IN ('PENDING', 'APPROVED')
                """,
                Timestamp.from(now),
                confirmationId);
    }

    private static void verifyUser(ToolConfirmation confirmation, String userId) {
        if (!confirmation.userId().equals(userId)) {
            throw new ToolConfirmationAccessDeniedException();
        }
    }

    private static ToolConfirmationInvalidStateException invalidTransition(
            ToolConfirmation confirmation, String action) {
        return new ToolConfirmationInvalidStateException(
                "Cannot "
                        + action
                        + " tool confirmation "
                        + confirmation.id()
                        + " from status "
                        + confirmation.status());
    }

    private static ToolConfirmation map(ResultSet rs) throws SQLException {
        return new ToolConfirmation(
                rs.getString("id"),
                rs.getString("active_key"),
                rs.getString("requested_execution_id"),
                rs.getString("requested_tool_call_id"),
                rs.getString("consumed_execution_id"),
                rs.getString("consumed_tool_call_id"),
                rs.getString("agent_id"),
                rs.getString("user_id"),
                rs.getString("session_id"),
                rs.getString("tool_name"),
                rs.getString("argument_fingerprint"),
                rs.getString("summary"),
                ToolConfirmationStatus.valueOf(rs.getString("status")),
                toInstant(rs.getTimestamp("expires_at")),
                toInstant(rs.getTimestamp("approved_at")),
                toInstant(rs.getTimestamp("rejected_at")),
                toInstant(rs.getTimestamp("consumed_at")),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
