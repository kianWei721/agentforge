CREATE TABLE af_session (
    id VARCHAR(64) NOT NULL,
    agent_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    session_key VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    next_sequence BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    last_message_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_af_session_identity (agent_id, user_id, session_key),
    INDEX idx_af_session_agent_user_last (agent_id, user_id, last_message_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE af_message (
    id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    sequence_no BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    source VARCHAR(64) NOT NULL DEFAULT '',
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_af_message_session_sequence (session_id, sequence_no),
    INDEX idx_af_message_session_created (session_id, created_at),
    CONSTRAINT fk_af_message_session
        FOREIGN KEY (session_id) REFERENCES af_session(id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
