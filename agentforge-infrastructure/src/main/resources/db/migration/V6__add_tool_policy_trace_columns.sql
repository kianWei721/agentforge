ALTER TABLE af_trace_event
    ADD COLUMN tool_risk_level VARCHAR(16) NULL AFTER outbound_outcome,
    ADD COLUMN tool_read_only BOOLEAN NULL AFTER tool_risk_level,
    ADD COLUMN policy_decision VARCHAR(32) NULL AFTER tool_read_only,
    ADD COLUMN policy_reason VARCHAR(128) NULL AFTER policy_decision;
