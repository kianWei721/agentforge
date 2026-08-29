ALTER TABLE af_trace_event
    ADD COLUMN tool_duration_ms BIGINT NULL AFTER model_time_ms,
    ADD COLUMN failure_code VARCHAR(64) NULL AFTER tool_duration_ms,
    ADD COLUMN retryable BOOLEAN NULL AFTER failure_code,
    ADD COLUMN governance_decision VARCHAR(32) NULL AFTER retryable,
    ADD COLUMN governance_reason VARCHAR(128) NULL AFTER governance_decision,
    ADD COLUMN argument_fingerprint VARCHAR(64) NULL AFTER governance_reason,
    ADD COLUMN blocked_by_tool_call_id VARCHAR(128) NULL AFTER argument_fingerprint,
    ADD COLUMN blocked_by_failure_code VARCHAR(64) NULL AFTER blocked_by_tool_call_id;
