package com.agentforge.rag;

public record KnowledgeRerankDiagnostics(
        String rerankMode,
        int candidateCount,
        int acceptedCount,
        String decision,
        Double topRealScore,
        Integer topRealRank,
        String topRealCitationId,
        Integer topRealTextChars,
        String topRealTextSha256,
        Double noAnswerScore,
        Integer noAnswerRank) {}
