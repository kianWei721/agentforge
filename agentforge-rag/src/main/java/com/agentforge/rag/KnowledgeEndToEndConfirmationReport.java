package com.agentforge.rag;

import java.util.List;

public record KnowledgeEndToEndConfirmationReport(
        String decisionBoundary,
        String decision,
        boolean promotionEligible,
        boolean candidateLeaderConfirmed,
        String candidateLeaderVariant,
        String challengerQueryMode,
        int challengerRrfK,
        int productionRrfK,
        KnowledgeEvaluationVariantReport control,
        KnowledgeEvaluationVariantReport challenger,
        List<String> newFailureCases,
        List<String> resolvedFailureCases,
        double recallAt1Delta,
        double recallAt3Delta,
        double recallAt5Delta,
        double meanReciprocalRankDelta,
        double noAnswerAccuracyDelta,
        double overallAccuracyDelta) {

    public KnowledgeEndToEndConfirmationReport {
        newFailureCases =
                newFailureCases == null ? List.of() : List.copyOf(newFailureCases);
        resolvedFailureCases =
                resolvedFailureCases == null ? List.of() : List.copyOf(resolvedFailureCases);
    }
}
