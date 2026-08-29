ALTER TABLE af_trace_event
    ADD COLUMN outbound_protocol VARCHAR(32) NULL AFTER blocked_by_failure_code,
    ADD COLUMN downstream_system VARCHAR(64) NULL AFTER outbound_protocol,
    ADD COLUMN outbound_attempts INT NULL AFTER downstream_system,
    ADD COLUMN http_status INT NULL AFTER outbound_attempts,
    ADD COLUMN business_code INT NULL AFTER http_status,
    ADD COLUMN downstream_duration_ms BIGINT NULL AFTER business_code,
    ADD COLUMN outbound_outcome VARCHAR(64) NULL AFTER downstream_duration_ms;
