package com.agentforge.rag;

public record KnowledgeEvaluationReport(
        String datasetVersion,
        String evaluationIndex,
        int documentCount,
        int caseCount,
        KnowledgeCandidateBenchmarkReport candidateBenchmark,
        KnowledgeEvaluationVariantReport baseline,
        KnowledgeEvaluationVariantReport rewrite,
        double recallAt1Delta,
        double recallAt3Delta,
        double recallAt5Delta,
        double meanReciprocalRankDelta,
        double noAnswerAccuracyDelta,
        long durationMs) {}
