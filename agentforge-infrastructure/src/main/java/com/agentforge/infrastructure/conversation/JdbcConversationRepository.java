package com.agentforge.infrastructure.conversation;

import com.agentforge.conversation.ConversationMessage;
import com.agentforge.conversation.ConversationNotFoundException;
import com.agentforge.conversation.ConversationRepository;
import com.agentforge.conversation.ConversationRole;
import com.agentforge.conversation.ConversationSession;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

public class JdbcConversationRepository implements ConversationRepository {

    private static final String SESSION_COLUMNS =
            "id, agent_id, user_id, session_key, status, created_at, updated_at, last_message_at";

    private static final RowMapper<ConversationSession> SESSION_ROW_MAPPER =
            (rs, rowNum) -> mapSession(rs);

    private static final RowMapper<ConversationMessage> MESSAGE_ROW_MAPPER =
            (rs, rowNum) -> mapMessage(rs);

    private final JdbcTemplate jdbcTemplate;

    public JdbcConversationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public ConversationSession getOrCreateSession(
            String agentId, String userId, String sessionKey) {
        Instant now = Instant.now();
        String candidateId = UUID.randomUUID().toString();

        jdbcTemplate.update(
                """
                INSERT INTO af_session (
                    id, agent_id, user_id, session_key, status,
                    next_sequence, created_at, updated_at, last_message_at
                ) VALUES (?, ?, ?, ?, 'ACTIVE', 1, ?, ?, NULL)
                ON DUPLICATE KEY UPDATE id = id
                """,
                candidateId,
                agentId,
                userId,
                sessionKey,
                Timestamp.from(now),
                Timestamp.from(now));

        return findSession(agentId, userId, sessionKey)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Session insert succeeded but row could not be reloaded"));
    }

    @Override
    public Optional<ConversationSession> findSession(
            String agentId, String userId, String sessionKey) {
        List<ConversationSession> rows =
                jdbcTemplate.query(
                        "SELECT "
                                + SESSION_COLUMNS
                                + " FROM af_session"
                                + " WHERE agent_id = ? AND user_id = ? AND session_key = ?",
                        SESSION_ROW_MAPPER,
                        agentId,
                        userId,
                        sessionKey);
        return rows.stream().findFirst();
    }

    @Override
    public List<ConversationSession> findSessions(String agentId, String userId) {
        return jdbcTemplate.query(
                "SELECT "
                        + SESSION_COLUMNS
                        + " FROM af_session"
                        + " WHERE agent_id = ? AND user_id = ?"
                        + " ORDER BY last_message_at DESC, created_at DESC, id DESC",
                SESSION_ROW_MAPPER,
                agentId,
                userId);
    }

    @Override
    @Transactional
    public ConversationMessage appendMessage(
            String sessionId, ConversationRole role, String content, String source) {
        List<Long> sequences =
                jdbcTemplate.query(
                        "SELECT next_sequence FROM af_session WHERE id = ? FOR UPDATE",
                        (rs, rowNum) -> rs.getLong("next_sequence"),
                        sessionId);

        if (sequences.isEmpty()) {
            throw ConversationNotFoundException.byInternalId(sessionId);
        }

        long sequenceNo = sequences.get(0);
        Instant now = Instant.now();
        ConversationMessage message =
                new ConversationMessage(
                        UUID.randomUUID().toString(),
                        sessionId,
                        sequenceNo,
                        role,
                        content,
                        source,
                        now);

        jdbcTemplate.update(
                """
                INSERT INTO af_message (
                    id, session_id, sequence_no, role, content, source, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                message.id(),
                message.sessionId(),
                message.sequenceNo(),
                message.role().name(),
                message.content(),
                message.source(),
                Timestamp.from(message.createdAt()));

        int updated =
                jdbcTemplate.update(
                        """
                        UPDATE af_session
                        SET next_sequence = ?,
                            updated_at = ?,
                            last_message_at = ?
                        WHERE id = ?
                        """,
                        sequenceNo + 1,
                        Timestamp.from(now),
                        Timestamp.from(now),
                        sessionId);

        if (updated != 1) {
            throw new IllegalStateException("Failed to advance conversation sequence: " + sessionId);
        }

        return message;
    }

    @Override
    public List<ConversationMessage> findMessages(String sessionId) {
        return jdbcTemplate.query(
                """
                SELECT id, session_id, sequence_no, role, content, source, created_at
                FROM af_message
                WHERE session_id = ?
                ORDER BY sequence_no
                """,
                MESSAGE_ROW_MAPPER,
                sessionId);
    }

    private static ConversationSession mapSession(ResultSet rs) throws SQLException {
        return new ConversationSession(
                rs.getString("id"),
                rs.getString("agent_id"),
                rs.getString("user_id"),
                rs.getString("session_key"),
                rs.getString("status"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")),
                toInstant(rs.getTimestamp("last_message_at")));
    }

    private static ConversationMessage mapMessage(ResultSet rs) throws SQLException {
        return new ConversationMessage(
                rs.getString("id"),
                rs.getString("session_id"),
                rs.getLong("sequence_no"),
                ConversationRole.valueOf(rs.getString("role")),
                rs.getString("content"),
                rs.getString("source"),
                toInstant(rs.getTimestamp("created_at")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
