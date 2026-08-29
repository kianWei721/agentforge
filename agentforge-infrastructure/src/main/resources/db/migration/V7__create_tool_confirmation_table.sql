CREATE TABLE af_tool_confirmation (
    id VARCHAR(64) NOT NULL,
    active_key VARCHAR(64) NULL,
    requested_execution_id VARCHAR(64) NOT NULL,
    requested_tool_call_id VARCHAR(128) NOT NULL,
    consumed_execution_id VARCHAR(64) NULL,
    consumed_tool_call_id VARCHAR(128) NULL,
    agent_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    argument_fingerprint VARCHAR(64) NOT NULL,
    summary VARCHAR(512) NOT NULL,
    status VARCHAR(16) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    approved_at DATETIME(6) NULL,
    rejected_at DATETIME(6) NULL,
    consumed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_af_tool_confirmation_active_key (active_key),
    INDEX idx_af_tool_confirmation_user_status (user_id, status, created_at),
    INDEX idx_af_tool_confirmation_binding (
        agent_id, user_id, session_id, tool_name, argument_fingerprint
    ),
    INDEX idx_af_tool_confirmation_requested_execution (requested_execution_id),
    INDEX idx_af_tool_confirmation_consumed_execution (consumed_execution_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


ALTER TABLE af_trace_event
    ADD COLUMN confirmation_id VARCHAR(64) NULL AFTER policy_reason,
    ADD COLUMN confirmation_status VARCHAR(16) NULL AFTER confirmation_id;

CREATE INDEX idx_af_trace_confirmation_id
    ON af_trace_event (confirmation_id);
