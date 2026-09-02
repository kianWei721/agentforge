package com.agentforge.rag;

import java.util.List;

public record KnowledgeCandidateEvaluationCaseResult(
        String name,
        String category,
        String query,
        List<String> expectedDocumentIds,
        List<String> actualDocumentIds,
        Integer firstRelevantRank,
        double reciprocalRank,
        boolean hitAt1,
        boolean hitAt3,
        boolean hitAt5,
        boolean passed) {

    public KnowledgeCandidateEvaluationCaseResult {
        expectedDocumentIds =
                expectedDocumentIds == null ? List.of() : List.copyOf(expectedDocumentIds);
        actualDocumentIds =
                actualDocumentIds == null ? List.of() : List.copyOf(actualDocumentIds);
    }
}
