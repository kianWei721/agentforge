package com.agentforge.infrastructure.channel;

import com.agentforge.channel.ChannelInbound;
import com.agentforge.channel.ChannelIntent;
import com.agentforge.channel.ChannelInboundRepository;
import com.agentforge.channel.ChannelInboundStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcChannelInboundRepository implements ChannelInboundRepository {

    private static final String COLUMNS =
            "id, channel, bot_id, msg_id, chat_type, chat_id, sender_userid, agent_id, user_id, "
                    + "session_key, execution_id, status, error_code, created_at, updated_at, "
                    + "intent, intent_source, intent_confidence, intent_request_id";

    private final JdbcTemplate jdbcTemplate;

    public JdbcChannelInboundRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean insertIfAbsent(ChannelInbound inbound) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO af_channel_inbound (" + COLUMNS + ") "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    inbound.id(),
                    inbound.channel(),
                    inbound.botId(),
                    inbound.msgId(),
                    inbound.chatType(),
                    inbound.chatId(),
                    inbound.senderUserId(),
                    inbound.agentId(),
                    inbound.userId(),
                    inbound.sessionKey(),
                    inbound.executionId(),
                    inbound.status().name(),
                    inbound.errorCode(),
                    Timestamp.from(inbound.createdAt()),
                    Timestamp.from(inbound.updatedAt()),
                    inbound.intent() == null ? null : inbound.intent().intent(),
                    inbound.intent() == null ? null : inbound.intent().source(),
                    inbound.intent() == null ? null : confidence(inbound.intent()),
                    inbound.intent() == null ? null : inbound.intent().gatewayRequestId());
            return true;
        } catch (DuplicateKeyException redelivered) {
            return false;
        }
    }

    @Override
    public void complete(
            String id,
            ChannelInboundStatus status,
            String executionId,
            String errorCode,
            Instant updatedAt) {
        jdbcTemplate.update(
                """
                UPDATE af_channel_inbound
                SET status = ?, execution_id = COALESCE(?, execution_id), error_code = ?,
                    updated_at = ?
                WHERE id = ?
                """,
                status.name(),
                executionId,
                errorCode,
                Timestamp.from(updatedAt),
                id);
    }

    @Override
    public void recordIntent(String id, ChannelIntent intent) {
        jdbcTemplate.update(
                """
                UPDATE af_channel_inbound
                SET intent = ?, intent_source = ?, intent_confidence = ?, intent_request_id = ?
                WHERE id = ?
                """,
                intent.intent(),
                intent.source(),
                confidence(intent),
                intent.gatewayRequestId(),
                id);
    }

    /** DECIMAL(4, 3): three places are as much as a model's self-reported confidence means. */
    private static BigDecimal confidence(ChannelIntent intent) {
        return intent.confidence() == null
                ? null
                : BigDecimal.valueOf(intent.confidence()).setScale(3, RoundingMode.HALF_UP);
    }

    @Override
    public Optional<ChannelInbound> find(String channel, String botId, String msgId) {
        List<ChannelInbound> rows =
                jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM af_channel_inbound "
                                + "WHERE channel = ? AND bot_id = ? AND msg_id = ?",
                        (rs, rowNum) -> map(rs),
                        channel,
                        botId,
                        msgId);
        return rows.stream().findFirst();
    }

    private static ChannelInbound map(ResultSet rs) throws SQLException {
        return new ChannelInbound(
                rs.getString("id"),
                rs.getString("channel"),
                rs.getString("bot_id"),
                rs.getString("msg_id"),
                rs.getString("chat_type"),
                rs.getString("chat_id"),
                rs.getString("sender_userid"),
                rs.getString("agent_id"),
                rs.getString("user_id"),
                rs.getString("session_key"),
                rs.getString("execution_id"),
                ChannelInboundStatus.valueOf(rs.getString("status")),
                rs.getString("error_code"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                intent(rs));
    }

    private static ChannelIntent intent(ResultSet rs) throws SQLException {
        String intent = rs.getString("intent");
        if (intent == null) {
            return null;
        }
        BigDecimal confidence = rs.getBigDecimal("intent_confidence");
        return new ChannelIntent(
                intent,
                rs.getString("intent_source"),
                confidence == null ? null : confidence.doubleValue(),
                rs.getString("intent_request_id"));
    }
}
