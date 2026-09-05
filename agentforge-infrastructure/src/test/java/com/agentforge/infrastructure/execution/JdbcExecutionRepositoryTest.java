package com.agentforge.infrastructure.execution;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.observability.execution.AgentExecution;
import com.agentforge.observability.execution.ExecutionStatus;
import com.agentforge.observability.execution.ExecutionTraceEvent;
import com.agentforge.observability.execution.TraceEventInput;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcExecutionRepositoryTest {

    private JdbcExecutionRepository repository;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(
                "jdbc:h2:mem:execution;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP TABLE IF EXISTS af_trace_event");
        jdbcTemplate.execute("DROP TABLE IF EXISTS af_execution");

        jdbcTemplate.execute(
                """
                CREATE TABLE af_execution (
                    id VARCHAR(64) PRIMARY KEY,
                    session_id VARCHAR(64) NOT NULL,
                    agent_id VARCHAR(64) NOT NULL,
                    user_id VARCHAR(128) NOT NULL,
                    user_message_id VARCHAR(64) NOT NULL,
                    assistant_message_id VARCHAR(64),
                    model_provider VARCHAR(64) NOT NULL,
                    model_name VARCHAR(128) NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    next_trace_sequence BIGINT NOT NULL,
                    started_at TIMESTAMP(6) NOT NULL,
                    completed_at TIMESTAMP(6),
                    duration_ms BIGINT,
                    error_type VARCHAR(128),
                    error_message CLOB
                )
                """);

        jdbcTemplate.execute(
                """
                CREATE TABLE af_trace_event (
                    id VARCHAR(64) PRIMARY KEY,
                    execution_id VARCHAR(64) NOT NULL,
                    sequence_no BIGINT NOT NULL,
                    event_type VARCHAR(64) NOT NULL,
                    source VARCHAR(64) NOT NULL,
                    content CLOB NOT NULL,
                    tool_call_id VARCHAR(128),
                    tool_name VARCHAR(128),
                    reply_id VARCHAR(128),
                    state VARCHAR(32),
                    usage_text CLOB,
                    input_tokens INT,
                    output_tokens INT,
                    cached_tokens INT,
                    model_time_ms BIGINT,
                    tool_duration_ms BIGINT,
                    failure_code VARCHAR(64),
                    retryable BOOLEAN,
                    governance_decision VARCHAR(32),
                    governance_reason VARCHAR(128),
                    argument_fingerprint VARCHAR(64),
                    blocked_by_tool_call_id VARCHAR(128),
                    blocked_by_failure_code VARCHAR(64),
                    outbound_protocol VARCHAR(32),
                    downstream_system VARCHAR(64),
                    outbound_attempts INT,
                    http_status INT,
                    business_code INT,
                    downstream_duration_ms BIGINT,
                    outbound_outcome VARCHAR(64),
                    tool_risk_level VARCHAR(16),
                    tool_read_only BOOLEAN,
                    policy_decision VARCHAR(32),
                    policy_reason VARCHAR(128),
                    confirmation_id VARCHAR(64),
                    confirmation_status VARCHAR(16),
                    retrieval_budget_decision VARCHAR(32),
                    retrieval_budget_reason VARCHAR(128),
                    retrieval_budget_limit INT,
                    retrieval_budget_used INT,
                    retrieval_budget_remaining INT,
                    occurred_at TIMESTAMP(6) NOT NULL,
                    UNIQUE (execution_id, sequence_no)
                )
                """);

        repository = new JdbcExecutionRepository(jdbcTemplate);
    }

    @Test
    void shouldPersistOrderedTraceAndSuccessfulExecution() {
        AgentExecution execution =
                repository.start(
                        "session-1",
                        "agent-1",
                        "user-1",
                        "message-1",
                        "dashscope",
                        "qwen3.8-flash");

        assertEquals(ExecutionStatus.RUNNING, execution.status());

        ExecutionTraceEvent first =
                repository.appendTrace(
                        execution.id(),
                        new TraceEventInput(
                                "model_call_start",
                                "agentscope-harness",
                                "",
                                null,
                                null,
                                "reply-1",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null));
        ExecutionTraceEvent second =
                repository.appendTrace(
                        execution.id(),
                        new TraceEventInput(
                                "tool_call_start",
                                "agentscope-harness",
                                "",
                                "tool-call-1",
                                "query_device_status",
                                "reply-1",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null));

        ExecutionTraceEvent usage =
                repository.appendTrace(
                        execution.id(),
                        new TraceEventInput(
                                "model_call_end",
                                "agentscope-harness",
                                "",
                                null,
                                null,
                                "reply-1",
                                null,
                                null,
                                120,
                                8,
                                20,
                                315L));

        ExecutionTraceEvent governedTool =
                repository.appendTrace(
                        execution.id(),
                        new TraceEventInput(
                                "tool_result_end",
                                "agentscope-harness",
                                "",
                                "tool-call-1",
                                "query_device_status",
                                "reply-2",
                                "error",
                                null,
                                null,
                                null,
                                null,
                                null,
                                21L,
                                "REPEATED_NON_RETRYABLE_CALL",
                                false,
                                "BLOCK",
                                "repeated_non_retryable_failure",
                                "abc123",
                                "tool-call-0",
                                "FORBIDDEN",
                                "HTTP",
                                "external-iot",
                                1,
                                200,
                                403,
                                148L,
                                "FORBIDDEN",
                                "HIGH",
                                false,
                                "REQUIRE_CONFIRMATION",
                                "write_tool_requires_confirmation",
                                "confirmation-1",
                                "PENDING",
                                null,
                                null,
                                null,
                                null,
                                null));

        assertEquals(1L, first.sequenceNo());
        assertEquals(2L, second.sequenceNo());
        assertEquals(3L, usage.sequenceNo());
        assertEquals(4L, governedTool.sequenceNo());

        AgentExecution completed = repository.markSucceeded(execution.id(), "message-2");
        assertEquals(ExecutionStatus.SUCCEEDED, completed.status());
        assertEquals("message-2", completed.assistantMessageId());
        assertNotNull(completed.completedAt());
        assertNotNull(completed.durationMs());

        List<ExecutionTraceEvent> traces = repository.findTraces(execution.id());
        assertEquals(4, traces.size());
        assertEquals("model_call_start", traces.get(0).eventType());
        assertEquals("query_device_status", traces.get(1).toolName());
        assertEquals("reply-1", traces.get(1).replyId());
        assertEquals(120, traces.get(2).inputTokens());
        assertEquals(8, traces.get(2).outputTokens());
        assertEquals(20, traces.get(2).cachedTokens());
        assertEquals(315L, traces.get(2).modelTimeMs());
        assertEquals(21L, traces.get(3).toolDurationMs());
        assertEquals("REPEATED_NON_RETRYABLE_CALL", traces.get(3).failureCode());
        assertFalse(traces.get(3).retryable());
        assertEquals("BLOCK", traces.get(3).governanceDecision());
        assertEquals("repeated_non_retryable_failure", traces.get(3).governanceReason());
        assertEquals("abc123", traces.get(3).argumentFingerprint());
        assertEquals("tool-call-0", traces.get(3).blockedByToolCallId());
        assertEquals("FORBIDDEN", traces.get(3).blockedByFailureCode());
        assertEquals("HTTP", traces.get(3).outboundProtocol());
        assertEquals("external-iot", traces.get(3).downstreamSystem());
        assertEquals(1, traces.get(3).outboundAttempts());
        assertEquals(200, traces.get(3).httpStatus());
        assertEquals(403, traces.get(3).businessCode());
        assertEquals(148L, traces.get(3).downstreamDurationMs());
        assertEquals("FORBIDDEN", traces.get(3).outboundOutcome());
        assertEquals("HIGH", traces.get(3).toolRiskLevel());
        assertFalse(traces.get(3).toolReadOnly());
        assertEquals("REQUIRE_CONFIRMATION", traces.get(3).policyDecision());
        assertEquals("write_tool_requires_confirmation", traces.get(3).policyReason());
        assertEquals("confirmation-1", traces.get(3).confirmationId());
        assertEquals("PENDING", traces.get(3).confirmationStatus());
    }

    @Test
    void shouldRecordFailureAndKeepTerminalTransitionIdempotent() {
        AgentExecution execution =
                repository.start(
                        "session-2",
                        "agent-1",
                        "user-1",
                        "message-3",
                        "dashscope",
                        "qwen3.8-flash");

        AgentExecution failed =
                repository.markFailed(
                        execution.id(),
                        "IllegalStateException",
                        "tool failed");

        assertEquals(ExecutionStatus.FAILED, failed.status());
        assertEquals("IllegalStateException", failed.errorType());
        assertEquals("tool failed", failed.errorMessage());

        AgentExecution unchanged = repository.markSucceeded(execution.id(), "message-4");
        assertEquals(ExecutionStatus.FAILED, unchanged.status());
        assertNull(unchanged.assistantMessageId());
    }
}
