package com.agentforge.rag;

public interface KnowledgeQueryRewriter {

    KnowledgeQueryRewriteResult rewrite(String query);

    /** Gateway-aware implementations report their calls into {@code context}. */
    default KnowledgeQueryRewriteResult rewrite(String query, KnowledgeModelCallContext context) {
        return rewrite(query);
    }

    default String mode() {
        return "UNKNOWN";
    }
}
