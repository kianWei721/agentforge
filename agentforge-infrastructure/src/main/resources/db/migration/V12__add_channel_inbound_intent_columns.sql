-- How each inbound message was routed (V0.12 intent routing). HANDOFF and refused messages start
-- no execution, so the inbound row is the only place their intent is kept. All columns are
-- nullable and appended at the end: rows from before routing, or with routing off, have none.
--
-- Rollback:
--   ALTER TABLE af_channel_inbound
--       DROP COLUMN intent, DROP COLUMN intent_source,
--       DROP COLUMN intent_confidence, DROP COLUMN intent_request_id;
-- Rows recorded as HANDOFF stay readable as text but are unknown to code from before V0.12.
ALTER TABLE af_channel_inbound
    ADD COLUMN intent VARCHAR(32) NULL,
    ADD COLUMN intent_source VARCHAR(32) NULL,
    ADD COLUMN intent_confidence DECIMAL(4, 3) NULL,
    ADD COLUMN intent_request_id VARCHAR(64) NULL;
