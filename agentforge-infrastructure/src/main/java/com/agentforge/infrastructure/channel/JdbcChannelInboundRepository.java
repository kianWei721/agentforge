package com.agentforge.infrastructure.channel;

import com.agentforge.channel.ChannelInbound;
import com.agentforge.channel.ChannelInboundRepository;
import com.agentforge.channel.ChannelInboundStatus;
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
                    + "session_key, execution_id, status, error_code, created_at, updated_at";

    private final JdbcTemplate jdbcTemplate;

    public JdbcChannelInboundRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean insertIfAbsent(ChannelInbound inbound) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO af_channel_inbound (" + COLUMNS + ") "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
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
                    Timestamp.from(inbound.updatedAt()));
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
                rs.getTimestamp("updated_at").toInstant());
    }
}
