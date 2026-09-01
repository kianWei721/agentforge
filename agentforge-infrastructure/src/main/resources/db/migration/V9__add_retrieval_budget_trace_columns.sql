ALTER TABLE af_trace_event
    ADD COLUMN retrieval_budget_decision VARCHAR(32) NULL AFTER confirmation_status,
    ADD COLUMN retrieval_budget_reason VARCHAR(128) NULL AFTER retrieval_budget_decision,
    ADD COLUMN retrieval_budget_limit INT NULL AFTER retrieval_budget_reason,
    ADD COLUMN retrieval_budget_used INT NULL AFTER retrieval_budget_limit,
    ADD COLUMN retrieval_budget_remaining INT NULL AFTER retrieval_budget_used;
