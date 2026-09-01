package com.agentforge.rag;

public record KnowledgeRerankDiagnostics(
        String rerankMode,
        int candidateCount,
        int acceptedCount,
        String decision,
        Double topRealScore,
        String topRealCitationId,
        Double noAnswerScore,
        Integer noAnswerRank) {}
