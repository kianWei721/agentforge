package com.agentforge.rag;

import java.util.List;

public interface KnowledgeAnswerabilityJudge {

    KnowledgeAnswerabilityResult judge(
            String query, List<KnowledgeSearchHit> candidates);

    /** Gateway-aware implementations report their calls into {@code context}. */
    default KnowledgeAnswerabilityResult judge(
            String query, List<KnowledgeSearchHit> candidates, KnowledgeModelCallContext context) {
        return judge(query, candidates);
    }

    default String mode() {
        return "UNKNOWN";
    }
}
