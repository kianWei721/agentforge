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
                                null,
                                null,
                                null));

        assertEquals(1L, first.sequenceNo());
        assertEquals(2L, second.sequenceNo());

        AgentExecution completed = repository.markSucceeded(execution.id(), "message-2");
        assertEquals(ExecutionStatus.SUCCEEDED, completed.status());
        assertEquals("message-2", completed.assistantMessageId());
        assertNotNull(completed.completedAt());
        assertNotNull(completed.durationMs());

        List<ExecutionTraceEvent> traces = repository.findTraces(execution.id());
        assertEquals(2, traces.size());
        assertEquals("model_call_start", traces.get(0).eventType());
        assertEquals("query_device_status", traces.get(1).toolName());
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
