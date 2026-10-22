-- Document-level ACL. Chunks inherit it; the search index copies it for pre-filtering, but this
-- table stays the source of truth that the post-retrieval guard re-checks.
-- Existing documents become INTERNAL (staff only) until someone decides otherwise.
ALTER TABLE af_knowledge_document
    ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'INTERNAL' AFTER source_ref,
    ADD COLUMN customer_id VARCHAR(64) NULL AFTER visibility,
    ADD INDEX idx_af_knowledge_document_access (visibility, customer_id),
    ADD CONSTRAINT ck_af_knowledge_document_access CHECK (
        (visibility = 'CUSTOMER' AND customer_id IS NOT NULL)
        OR (visibility IN ('INTERNAL', 'PUBLIC') AND customer_id IS NULL));
