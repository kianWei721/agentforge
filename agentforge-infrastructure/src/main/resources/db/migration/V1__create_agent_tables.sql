CREATE TABLE af_agent (
    id VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512) NOT NULL DEFAULT '',
    system_prompt TEXT NOT NULL,
    model_provider VARCHAR(64) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_af_agent_enabled (enabled),
    INDEX idx_af_agent_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE af_agent_tool (
    agent_id VARCHAR(64) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    PRIMARY KEY (agent_id, tool_name),
    INDEX idx_af_agent_tool_sort (agent_id, sort_order),
    CONSTRAINT fk_af_agent_tool_agent
        FOREIGN KEY (agent_id) REFERENCES af_agent(id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
