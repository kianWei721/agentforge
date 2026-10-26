-- Hand-off tickets (V0.14): one open ticket per session. Messages handed to a person while it is
-- open are merged into it; active_key holds the session key while it is open and is NULL once it
-- is resolved, so the unique key allows one open ticket per session and any number of closed ones.
-- The row is also the outbox of its staff-group notice (notify_*). A HANDOFF row in
-- af_channel_inbound and its ticket are written in one transaction.
-- Session keys and chat ids are case-sensitive, hence the binary collation on them.
--
-- Rollback:
--   DROP TABLE af_channel_handoff;
-- af_channel_inbound is untouched; its HANDOFF rows stay as they are.
CREATE TABLE af_channel_handoff (
    id VARCHAR(64) NOT NULL,
    active_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    channel VARCHAR(32) NOT NULL,
    bot_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    chat_type VARCHAR(16) NOT NULL,
    chat_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    session_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    customer_id VARCHAR(128) NULL,
    sender_userid VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    first_inbound_id VARCHAR(64) NOT NULL,
    last_inbound_id VARCHAR(64) NOT NULL,
    message_count INT NOT NULL,
    intent VARCHAR(32) NOT NULL,
    intent_source VARCHAR(32) NOT NULL,
    intent_confidence DECIMAL(4, 3) NULL,
    excerpt VARCHAR(255) NULL,
    status VARCHAR(16) NOT NULL,
    claimed_by VARCHAR(128) NULL,
    claimed_at DATETIME(6) NULL,
    resolved_by VARCHAR(128) NULL,
    resolved_at DATETIME(6) NULL,
    resolution VARCHAR(16) NULL,
    resolution_note VARCHAR(512) NULL,
    notify_status VARCHAR(16) NOT NULL,
    notify_attempts INT NOT NULL,
    next_notify_at DATETIME(6) NULL,
    notified_at DATETIME(6) NULL,
    notify_error VARCHAR(128) NULL,
    last_message_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_af_channel_handoff_active (active_key),
    UNIQUE KEY uk_af_channel_handoff_first_inbound (first_inbound_id),
    INDEX idx_af_channel_handoff_status_created (status, created_at),
    INDEX idx_af_channel_handoff_notify (notify_status, next_notify_at),
    INDEX idx_af_channel_handoff_session (session_key, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
