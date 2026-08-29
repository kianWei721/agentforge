package com.agentforge.infrastructure.execution;

import com.agentforge.observability.execution.AgentExecution;
import com.agentforge.observability.execution.ExecutionNotFoundException;
import com.agentforge.observability.execution.ExecutionRepository;
import com.agentforge.observability.execution.ExecutionStatus;
import com.agentforge.observability.execution.ExecutionTraceEvent;
import com.agentforge.observability.execution.TraceEventInput;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

public class JdbcExecutionRepository implements ExecutionRepository {

    private static final String EXECUTION_COLUMNS =
            "id, session_id, agent_id, user_id, user_message_id, assistant_message_id, "
                    + "model_provider, model_name, status, started_at, completed_at, duration_ms, "
                    + "error_type, error_message";

    private static final RowMapper<AgentExecution> EXECUTION_ROW_MAPPER =
            (rs, rowNum) -> mapExecution(rs);

    private static final RowMapper<ExecutionTraceEvent> TRACE_ROW_MAPPER =
            (rs, rowNum) -> mapTrace(rs);

    private final JdbcTemplate jdbcTemplate;

    public JdbcExecutionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public AgentExecution start(
            String sessionId,
            String agentId,
            String userId,
            String userMessageId,
            String modelProvider,
            String modelName) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();

        jdbcTemplate.update(
                """
                INSERT INTO af_execution (
                    id, session_id, agent_id, user_id, user_message_id,
                    assistant_message_id, model_provider, model_name, status,
                    next_trace_sequence, started_at, completed_at, duration_ms,
                    error_type, error_message
                ) VALUES (?, ?, ?, ?, ?, NULL, ?, ?, 'RUNNING', 1, ?, NULL, NULL, NULL, NULL)
                """,
                id,
                sessionId,
                agentId,
                userId,
                userMessageId,
                modelProvider,
                modelName,
                Timestamp.from(now));

        return findById(id).orElseThrow(() -> new ExecutionNotFoundException(id));
    }

    @Override
    public Optional<AgentExecution> findById(String executionId) {
        List<AgentExecution> rows =
                jdbcTemplate.query(
                        "SELECT " + EXECUTION_COLUMNS + " FROM af_execution WHERE id = ?",
                        EXECUTION_ROW_MAPPER,
                        executionId);
        return rows.stream().findFirst();
    }

    @Override
    public List<AgentExecution> findBySessionId(String sessionId) {
        return jdbcTemplate.query(
                "SELECT "
                        + EXECUTION_COLUMNS
                        + " FROM af_execution WHERE session_id = ?"
                        + " ORDER BY started_at DESC, id DESC",
                EXECUTION_ROW_MAPPER,
                sessionId);
    }

    @Override
    @Transactional
    public ExecutionTraceEvent appendTrace(String executionId, TraceEventInput input) {
        List<Long> sequences =
                jdbcTemplate.query(
                        "SELECT next_trace_sequence FROM af_execution WHERE id = ? FOR UPDATE",
                        (rs, rowNum) -> rs.getLong("next_trace_sequence"),
                        executionId);

        if (sequences.isEmpty()) {
            throw new ExecutionNotFoundException(executionId);
        }

        long sequenceNo = sequences.get(0);
        ExecutionTraceEvent event =
                new ExecutionTraceEvent(
                        UUID.randomUUID().toString(),
                        executionId,
                        sequenceNo,
                        input.eventType(),
                        input.source(),
                        input.content(),
                        input.toolCallId(),
                        input.toolName(),
                        input.replyId(),
                        input.state(),
                        input.usageText(),
                        input.inputTokens(),
                        input.outputTokens(),
                        input.cachedTokens(),
                        input.modelTimeMs(),
                        input.toolDurationMs(),
                        input.failureCode(),
                        input.retryable(),
                        input.governanceDecision(),
                        input.governanceReason(),
                        input.argumentFingerprint(),
                        input.blockedByToolCallId(),
                        input.blockedByFailureCode(),
                        Instant.now());

        jdbcTemplate.update(
                """
                INSERT INTO af_trace_event (
                    id, execution_id, sequence_no, event_type, source, content,
                    tool_call_id, tool_name, reply_id, state, usage_text,
                    input_tokens, output_tokens, cached_tokens, model_time_ms,
                    tool_duration_ms, failure_code, retryable, governance_decision,
                    governance_reason, argument_fingerprint, blocked_by_tool_call_id,
                    blocked_by_failure_code, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                event.id(),
                event.executionId(),
                event.sequenceNo(),
                event.eventType(),
                event.source(),
                event.content(),
                event.toolCallId(),
                event.toolName(),
                event.replyId(),
                event.state(),
                event.usageText(),
                event.inputTokens(),
                event.outputTokens(),
                event.cachedTokens(),
                event.modelTimeMs(),
                event.toolDurationMs(),
                event.failureCode(),
                event.retryable(),
                event.governanceDecision(),
                event.governanceReason(),
                event.argumentFingerprint(),
                event.blockedByToolCallId(),
                event.blockedByFailureCode(),
                Timestamp.from(event.occurredAt()));

        jdbcTemplate.update(
                "UPDATE af_execution SET next_trace_sequence = ? WHERE id = ?",
                sequenceNo + 1,
                executionId);

        return event;
    }

    @Override
    public List<ExecutionTraceEvent> findTraces(String executionId) {
        return jdbcTemplate.query(
                """
                SELECT id, execution_id, sequence_no, event_type, source, content,
                       tool_call_id, tool_name, reply_id, state, usage_text,
                       input_tokens, output_tokens, cached_tokens, model_time_ms,
                       tool_duration_ms, failure_code, retryable, governance_decision,
                       governance_reason, argument_fingerprint, blocked_by_tool_call_id,
                       blocked_by_failure_code, occurred_at
                FROM af_trace_event
                WHERE execution_id = ?
                ORDER BY sequence_no
                """,
                TRACE_ROW_MAPPER,
                executionId);
    }

    @Override
    @Transactional
    public AgentExecution markSucceeded(String executionId, String assistantMessageId) {
        return complete(
                executionId,
                ExecutionStatus.SUCCEEDED,
                assistantMessageId,
                null,
                null);
    }

    @Override
    @Transactional
    public AgentExecution markFailed(String executionId, String errorType, String errorMessage) {
        return complete(
                executionId,
                ExecutionStatus.FAILED,
                null,
                errorType,
                errorMessage);
    }

    @Override
    @Transactional
    public AgentExecution markCancelled(String executionId) {
        return complete(
                executionId,
                ExecutionStatus.CANCELLED,
                null,
                null,
                "Client cancelled the streaming response");
    }

    private AgentExecution complete(
            String executionId,
            ExecutionStatus targetStatus,
            String assistantMessageId,
            String errorType,
            String errorMessage) {
        List<AgentExecution> rows =
                jdbcTemplate.query(
                        "SELECT "
                                + EXECUTION_COLUMNS
                                + " FROM af_execution WHERE id = ? FOR UPDATE",
                        EXECUTION_ROW_MAPPER,
                        executionId);

        if (rows.isEmpty()) {
            throw new ExecutionNotFoundException(executionId);
        }

        AgentExecution current = rows.get(0);
        if (current.status() != ExecutionStatus.RUNNING) {
            return current;
        }

        Instant completedAt = Instant.now();
        long durationMs =
                Math.max(0L, Duration.between(current.startedAt(), completedAt).toMillis());

        jdbcTemplate.update(
                """
                UPDATE af_execution
                SET status = ?,
                    assistant_message_id = ?,
                    completed_at = ?,
                    duration_ms = ?,
                    error_type = ?,
                    error_message = ?
                WHERE id = ?
                """,
                targetStatus.name(),
                assistantMessageId,
                Timestamp.from(completedAt),
                durationMs,
                errorType,
                errorMessage,
                executionId);

        return findById(executionId)
                .orElseThrow(() -> new ExecutionNotFoundException(executionId));
    }

    private static AgentExecution mapExecution(ResultSet rs) throws SQLException {
        Long durationMs =
                rs.getObject("duration_ms") == null ? null : rs.getLong("duration_ms");

        return new AgentExecution(
                rs.getString("id"),
                rs.getString("session_id"),
                rs.getString("agent_id"),
                rs.getString("user_id"),
                rs.getString("user_message_id"),
                rs.getString("assistant_message_id"),
                rs.getString("model_provider"),
                rs.getString("model_name"),
                ExecutionStatus.valueOf(rs.getString("status")),
                toInstant(rs.getTimestamp("started_at")),
                toInstant(rs.getTimestamp("completed_at")),
                durationMs,
                rs.getString("error_type"),
                rs.getString("error_message"));
    }

    private static ExecutionTraceEvent mapTrace(ResultSet rs) throws SQLException {
        return new ExecutionTraceEvent(
                rs.getString("id"),
                rs.getString("execution_id"),
                rs.getLong("sequence_no"),
                rs.getString("event_type"),
                rs.getString("source"),
                rs.getString("content"),
                rs.getString("tool_call_id"),
                rs.getString("tool_name"),
                rs.getString("reply_id"),
                rs.getString("state"),
                rs.getString("usage_text"),
                nullableInteger(rs, "input_tokens"),
                nullableInteger(rs, "output_tokens"),
                nullableInteger(rs, "cached_tokens"),
                nullableLong(rs, "model_time_ms"),
                nullableLong(rs, "tool_duration_ms"),
                rs.getString("failure_code"),
                nullableBoolean(rs, "retryable"),
                rs.getString("governance_decision"),
                rs.getString("governance_reason"),
                rs.getString("argument_fingerprint"),
                rs.getString("blocked_by_tool_call_id"),
                rs.getString("blocked_by_failure_code"),
                toInstant(rs.getTimestamp("occurred_at")));
    }

    private static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : rs.getInt(column);
    }

    private static Boolean nullableBoolean(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : rs.getBoolean(column);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : rs.getLong(column);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
