package com.agentforge.infrastructure.channel;

import com.agentforge.channel.handoff.ChannelHandoff;
import com.agentforge.channel.handoff.ChannelHandoffRepository;
import com.agentforge.channel.handoff.HandoffOpened;
import com.agentforge.channel.handoff.HandoffOpening;
import com.agentforge.channel.handoff.HandoffResolution;
import com.agentforge.channel.handoff.HandoffStatus;
import com.agentforge.channel.handoff.NotifyStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Every state change is a conditional UPDATE on the state it expects, so two staff members, or a
 * staff member and the notice sender, never overwrite each other: the loser updates no row.
 */
public class JdbcChannelHandoffRepository implements ChannelHandoffRepository {

    private static final String COLUMNS =
            "id, channel, bot_id, chat_type, chat_id, session_key, user_id, customer_id, sender_userid, "
                    + "first_inbound_id, last_inbound_id, message_count, intent, intent_source, "
                    + "intent_confidence, excerpt, status, claimed_by, claimed_at, resolved_by, resolved_at, "
                    + "resolution, resolution_note, notify_status, notify_attempts, next_notify_at, "
                    + "notified_at, notify_error, last_message_at, created_at, updated_at";

    private static final int MAX_ERROR_CHARS = 128;

    private final JdbcTemplate jdbcTemplate;

    public JdbcChannelHandoffRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * One statement: a new ticket, or, on the session's open one (its {@code active_key}), the
     * message merged into it. A separate INSERT-then-UPDATE would deadlock under InnoDB when two
     * messages of one session merge at once: each failed INSERT holds a shared lock on the
     * duplicate key and each UPDATE then needs it exclusively. ON DUPLICATE KEY UPDATE takes the
     * exclusive lock directly. Whether it merged is read back from the id: a merge keeps the open
     * ticket's.
     */
    @Override
    public HandoffOpened open(HandoffOpening opening) {
        String id = UUID.randomUUID().toString();
        Timestamp at = Timestamp.from(opening.at());
        jdbcTemplate.update(
                "INSERT INTO af_channel_handoff (active_key, " + COLUMNS + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE message_count = message_count + 1, last_inbound_id = ?, "
                        + "last_message_at = ?, updated_at = ?",
                opening.sessionKey(),
                id,
                opening.channel(),
                opening.botId(),
                opening.chatType(),
                opening.chatId(),
                opening.sessionKey(),
                opening.userId(),
                opening.customerId(),
                opening.senderUserId(),
                opening.inboundId(),
                opening.inboundId(),
                1,
                opening.intent().intent(),
                opening.intent().source(),
                confidence(opening.intent().confidence()),
                opening.excerpt(),
                HandoffStatus.PENDING.name(),
                null,
                null,
                null,
                null,
                null,
                null,
                (opening.notifyStaff() ? NotifyStatus.PENDING : NotifyStatus.DISABLED).name(),
                0,
                null,
                null,
                null,
                at,
                at,
                at,
                opening.inboundId(),
                at,
                at);
        String open =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM af_channel_handoff WHERE active_key = ?", String.class, opening.sessionKey());
        return new HandoffOpened(open, !id.equals(open));
    }

    @Override
    public Optional<ChannelHandoff> find(String id) {
        return jdbcTemplate
                .query("SELECT " + COLUMNS + " FROM af_channel_handoff WHERE id = ?", (rs, rowNum) -> map(rs), id)
                .stream()
                .findFirst();
    }

    @Override
    public List<ChannelHandoff> list(HandoffStatus status, Instant createdAfter, int limit) {
        if (createdAfter == null) {
            return jdbcTemplate.query(
                    "SELECT " + COLUMNS + " FROM af_channel_handoff WHERE status = ? "
                            + "ORDER BY created_at, id LIMIT ?",
                    (rs, rowNum) -> map(rs),
                    status.name(),
                    limit);
        }
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM af_channel_handoff WHERE status = ? AND created_at >= ? "
                        + "ORDER BY created_at, id LIMIT ?",
                (rs, rowNum) -> map(rs),
                status.name(),
                Timestamp.from(createdAfter),
                limit);
    }

    /** A notice not yet sent is cancelled: it would ask the staff group to claim a claimed ticket. */
    @Override
    public boolean claim(String id, String staffUserId, Instant at) {
        Timestamp time = Timestamp.from(at);
        return jdbcTemplate.update(
                        """
                        UPDATE af_channel_handoff
                        SET status = 'CLAIMED', claimed_by = ?, claimed_at = ?,
                            notify_status = CASE WHEN notify_status = 'PENDING' THEN 'CANCELLED'
                                                 ELSE notify_status END,
                            updated_at = ?
                        WHERE id = ? AND status = 'PENDING'
                        """,
                        staffUserId,
                        time,
                        time,
                        id)
                == 1;
    }

    /**
     * Frees the session for its next ticket. A notice not yet sent is cancelled: nobody needs to
     * be told about a ticket that is already closed.
     */
    @Override
    public boolean resolve(
            String id, String staffUserId, HandoffResolution resolution, String note, Instant at) {
        Timestamp time = Timestamp.from(at);
        return jdbcTemplate.update(
                        """
                        UPDATE af_channel_handoff
                        SET status = 'RESOLVED', active_key = NULL, resolved_by = ?, resolved_at = ?,
                            resolution = ?, resolution_note = ?, claimed_by = COALESCE(claimed_by, ?),
                            notify_status = CASE WHEN notify_status = 'PENDING' THEN 'CANCELLED'
                                                 ELSE notify_status END,
                            updated_at = ?
                        WHERE id = ? AND (status = 'PENDING' OR (status = 'CLAIMED' AND claimed_by = ?))
                        """,
                        staffUserId,
                        time,
                        resolution.name(),
                        note,
                        staffUserId,
                        time,
                        id,
                        staffUserId)
                == 1;
    }

    @Override
    public List<ChannelHandoff> dueForNotification(Instant now, int limit) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM af_channel_handoff "
                        + "WHERE notify_status = 'PENDING' AND (next_notify_at IS NULL OR next_notify_at <= ?) "
                        + "ORDER BY created_at, id LIMIT ?",
                (rs, rowNum) -> map(rs),
                Timestamp.from(now),
                limit);
    }

    @Override
    public void markNotified(List<String> ids, Instant at) {
        Timestamp time = Timestamp.from(at);
        jdbcTemplate.batchUpdate(
                """
                UPDATE af_channel_handoff
                SET notify_status = 'SENT', notified_at = ?, next_notify_at = NULL, notify_error = NULL,
                    updated_at = ?
                WHERE id = ? AND notify_status = 'PENDING'
                """,
                ids.stream().map(id -> new Object[] {time, time, id}).toList());
    }

    @Override
    public void markNotifyRetry(String id, int attempts, Instant nextAt, String error, Instant at) {
        jdbcTemplate.update(
                """
                UPDATE af_channel_handoff
                SET notify_attempts = ?, next_notify_at = ?, notify_error = ?, updated_at = ?
                WHERE id = ? AND notify_status = 'PENDING'
                """,
                attempts,
                Timestamp.from(nextAt),
                cut(error),
                Timestamp.from(at),
                id);
    }

    @Override
    public void markNotifyFailed(String id, int attempts, String error, Instant at) {
        jdbcTemplate.update(
                """
                UPDATE af_channel_handoff
                SET notify_status = 'FAILED', notify_attempts = ?, next_notify_at = NULL,
                    notify_error = ?, updated_at = ?
                WHERE id = ? AND notify_status = 'PENDING'
                """,
                attempts,
                cut(error),
                Timestamp.from(at),
                id);
    }

    @Override
    public Map<HandoffStatus, Long> countOpen() {
        Map<HandoffStatus, Long> counts = new EnumMap<>(HandoffStatus.class);
        counts.put(HandoffStatus.PENDING, 0L);
        counts.put(HandoffStatus.CLAIMED, 0L);
        jdbcTemplate.query(
                "SELECT status, COUNT(*) AS n FROM af_channel_handoff "
                        + "WHERE status IN ('PENDING', 'CLAIMED') GROUP BY status",
                rs -> {
                    counts.put(HandoffStatus.valueOf(rs.getString("status")), rs.getLong("n"));
                });
        return counts;
    }

    private static String cut(String error) {
        return error == null || error.length() <= MAX_ERROR_CHARS ? error : error.substring(0, MAX_ERROR_CHARS);
    }

    /** DECIMAL(4, 3), as on the inbound table. */
    private static BigDecimal confidence(Double confidence) {
        return confidence == null ? null : BigDecimal.valueOf(confidence).setScale(3, RoundingMode.HALF_UP);
    }

    private static ChannelHandoff map(ResultSet rs) throws SQLException {
        BigDecimal confidence = rs.getBigDecimal("intent_confidence");
        String resolution = rs.getString("resolution");
        return new ChannelHandoff(
                rs.getString("id"),
                rs.getString("channel"),
                rs.getString("bot_id"),
                rs.getString("chat_type"),
                rs.getString("chat_id"),
                rs.getString("session_key"),
                rs.getString("user_id"),
                rs.getString("customer_id"),
                rs.getString("sender_userid"),
                rs.getString("first_inbound_id"),
                rs.getString("last_inbound_id"),
                rs.getInt("message_count"),
                rs.getString("intent"),
                rs.getString("intent_source"),
                confidence == null ? null : confidence.doubleValue(),
                rs.getString("excerpt"),
                HandoffStatus.valueOf(rs.getString("status")),
                rs.getString("claimed_by"),
                instant(rs, "claimed_at"),
                rs.getString("resolved_by"),
                instant(rs, "resolved_at"),
                resolution == null ? null : HandoffResolution.valueOf(resolution),
                rs.getString("resolution_note"),
                NotifyStatus.valueOf(rs.getString("notify_status")),
                rs.getInt("notify_attempts"),
                instant(rs, "next_notify_at"),
                instant(rs, "notified_at"),
                rs.getString("notify_error"),
                instant(rs, "last_message_at"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
