package com.agentforge.rag;

public record KnowledgeQueryRewriteDiagnostics(
        String rewriteMode,
        String originalQuery,
        String retrievalQuery,
        boolean changed,
        String decision,
        String reasonCode) {}
