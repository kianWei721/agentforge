CREATE TABLE af_knowledge_document (
    id VARCHAR(64) NOT NULL,
    title VARCHAR(256) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_ref VARCHAR(512) NOT NULL DEFAULT '',
    content MEDIUMTEXT NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_af_knowledge_document_enabled (enabled),
    INDEX idx_af_knowledge_document_source_type (source_type),
    INDEX idx_af_knowledge_document_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE af_knowledge_chunk (
    id VARCHAR(64) NOT NULL,
    document_id VARCHAR(64) NOT NULL,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    char_count INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_af_knowledge_chunk_document_index (document_id, chunk_index),
    INDEX idx_af_knowledge_chunk_document (document_id),
    CONSTRAINT fk_af_knowledge_chunk_document
        FOREIGN KEY (document_id) REFERENCES af_knowledge_document(id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
