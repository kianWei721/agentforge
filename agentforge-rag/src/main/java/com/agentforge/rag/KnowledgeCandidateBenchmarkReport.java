package com.agentforge.rag;

import java.util.List;

public record KnowledgeCandidateBenchmarkReport(
        int candidateWindow,
        List<Integer> rrfKValues,
        List<KnowledgeCandidateEvaluationVariantReport> variants) {

    public KnowledgeCandidateBenchmarkReport {
        rrfKValues = rrfKValues == null ? List.of() : List.copyOf(rrfKValues);
        variants = variants == null ? List.of() : List.copyOf(variants);
    }
}
