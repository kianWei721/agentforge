package com.agentforge.rag;

import java.util.List;

public record KnowledgeEvaluationCaseResult(
        String name,
        String category,
        String query,
        List<String> expectedDocumentIds,
        List<String> actualDocumentIds,
        boolean noAnswerExpected,
        boolean noAnswerActual,
        Integer firstRelevantRank,
        double reciprocalRank,
        boolean hitAt1,
        boolean hitAt3,
        boolean hitAt5,
        boolean passed) {

    public KnowledgeEvaluationCaseResult {
        expectedDocumentIds =
                expectedDocumentIds == null
                        ? List.of()
                        : List.copyOf(expectedDocumentIds);
        actualDocumentIds =
                actualDocumentIds == null
                        ? List.of()
                        : List.copyOf(actualDocumentIds);
    }
}
