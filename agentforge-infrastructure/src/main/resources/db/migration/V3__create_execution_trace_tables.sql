CREATE TABLE af_execution (
    id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    agent_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    user_message_id VARCHAR(64) NOT NULL,
    assistant_message_id VARCHAR(64) NULL,
    model_provider VARCHAR(64) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    next_trace_sequence BIGINT NOT NULL DEFAULT 1,
    started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    duration_ms BIGINT NULL,
    error_type VARCHAR(128) NULL,
    error_message TEXT NULL,
    PRIMARY KEY (id),
    INDEX idx_af_execution_session_started (session_id, started_at),
    INDEX idx_af_execution_agent_started (agent_id, started_at),
    INDEX idx_af_execution_status_started (status, started_at),
    CONSTRAINT fk_af_execution_session
        FOREIGN KEY (session_id) REFERENCES af_session(id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE af_trace_event (
    id VARCHAR(64) NOT NULL,
    execution_id VARCHAR(64) NOT NULL,
    sequence_no BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    source VARCHAR(64) NOT NULL DEFAULT '',
    content MEDIUMTEXT NOT NULL,
    tool_call_id VARCHAR(128) NULL,
    tool_name VARCHAR(128) NULL,
    reply_id VARCHAR(128) NULL,
    state VARCHAR(32) NULL,
    usage_text TEXT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_af_trace_execution_sequence (execution_id, sequence_no),
    INDEX idx_af_trace_execution_time (execution_id, occurred_at),
    INDEX idx_af_trace_tool_call (tool_call_id),
    CONSTRAINT fk_af_trace_execution
        FOREIGN KEY (execution_id) REFERENCES af_execution(id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
