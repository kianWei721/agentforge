package com.agentforge.rag;

public record KnowledgeQueryRewriteResult(
        String retrievalQuery,
        KnowledgeQueryRewriteDiagnostics diagnostics) {

    public KnowledgeQueryRewriteResult {
        if (retrievalQuery == null || retrievalQuery.isBlank()) {
            throw new IllegalArgumentException("retrievalQuery must not be blank");
        }
    }

    public static KnowledgeQueryRewriteResult original(
            String query,
            String mode,
            String decision,
            String reasonCode) {
        String normalized = query == null ? "" : query.trim();
        return new KnowledgeQueryRewriteResult(
                normalized,
                new KnowledgeQueryRewriteDiagnostics(
                        mode,
                        normalized,
                        normalized,
                        false,
                        decision,
                        reasonCode));
    }
}
