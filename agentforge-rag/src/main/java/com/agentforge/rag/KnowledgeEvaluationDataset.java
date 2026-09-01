package com.agentforge.rag;

import java.util.List;

public record KnowledgeEvaluationDataset(
        String version,
        String description,
        List<KnowledgeEvaluationDocument> documents,
        List<KnowledgeEvaluationCase> cases) {

    public KnowledgeEvaluationDataset {
        documents = documents == null ? List.of() : List.copyOf(documents);
        cases = cases == null ? List.of() : List.copyOf(cases);
    }
}
