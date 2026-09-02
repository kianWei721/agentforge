package com.agentforge.rag;

import java.util.List;

public record KnowledgeEvaluationStageDiagnostic(
        String caseName,
        String category,
        String queryMode,
        String originalQuery,
        String retrievalQuery,
        List<String> expectedDocumentIds,
        Integer bm25Rank,
        Integer vectorRank,
        Integer rrfRank,
        Integer rerankRank,
        Integer finalRank,
        List<String> bm25Top3,
        List<String> vectorTop3,
        List<String> rrfTop5,
        List<String> rerankTop5,
        List<String> finalDocumentIds,
        String judgeDecision,
        List<String> judgeExpectedReasonCodes,
        boolean judgeSupportedExpected,
        boolean finalNoAnswer) {

    public KnowledgeEvaluationStageDiagnostic {
        expectedDocumentIds =
                expectedDocumentIds == null ? List.of() : List.copyOf(expectedDocumentIds);
        bm25Top3 = bm25Top3 == null ? List.of() : List.copyOf(bm25Top3);
        vectorTop3 = vectorTop3 == null ? List.of() : List.copyOf(vectorTop3);
        rrfTop5 = rrfTop5 == null ? List.of() : List.copyOf(rrfTop5);
        rerankTop5 = rerankTop5 == null ? List.of() : List.copyOf(rerankTop5);
        finalDocumentIds =
                finalDocumentIds == null ? List.of() : List.copyOf(finalDocumentIds);
        judgeExpectedReasonCodes =
                judgeExpectedReasonCodes == null
                        ? List.of()
                        : List.copyOf(judgeExpectedReasonCodes);
    }
}
