package com.agentforge.rag;

import java.util.List;

public interface KnowledgeAnswerabilityJudge {

    KnowledgeAnswerabilityResult judge(
            String query, List<KnowledgeSearchHit> candidates);

    default String mode() {
        return "UNKNOWN";
    }
}
