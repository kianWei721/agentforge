package com.agentforge.rag;

import java.util.List;

public record KnowledgeEvaluationCase(
        String name,
        String category,
        String query,
        List<String> expectedDocumentIds) {

    public KnowledgeEvaluationCase {
        expectedDocumentIds =
                expectedDocumentIds == null
                        ? List.of()
                        : List.copyOf(expectedDocumentIds);
    }

    public boolean noAnswerExpected() {
        return expectedDocumentIds.isEmpty();
    }
}
