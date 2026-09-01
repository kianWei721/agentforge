package com.agentforge.rag;

public interface KnowledgeQueryRewriter {

    KnowledgeQueryRewriteResult rewrite(String query);

    default String mode() {
        return "UNKNOWN";
    }
}
