-- One row per inbound channel message (WeCom smart bot first). The unique key makes a redelivered
-- message run at most once; the row links the message to the execution it started.
-- Channel ids are case-sensitive, hence the binary collation on them.
CREATE TABLE af_channel_inbound (
    id VARCHAR(64) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    bot_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    msg_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    chat_type VARCHAR(16) NOT NULL,
    chat_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    sender_userid VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    agent_id VARCHAR(64) NULL,
    user_id VARCHAR(128) NULL,
    session_key VARCHAR(128) NULL,
    execution_id VARCHAR(64) NULL,
    status VARCHAR(16) NOT NULL,
    error_code VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_af_channel_inbound_message (channel, bot_id, msg_id),
    INDEX idx_af_channel_inbound_execution (execution_id),
    INDEX idx_af_channel_inbound_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
