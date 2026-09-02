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
        List<String> initialNewFailureCases,
        List<String> newFailureCases,
        List<String> resolvedFailureCases,
        List<KnowledgeRegressionRecheckCaseReport> regressionRechecks,
        List<String> confirmedRegressionCases,
        List<String> unstableRecheckCases,
        double recallAt1Delta,
        double recallAt3Delta,
        double recallAt5Delta,
        double meanReciprocalRankDelta,
        double noAnswerAccuracyDelta,
        double overallAccuracyDelta) {

    public KnowledgeEndToEndConfirmationReport {
        initialNewFailureCases =
                initialNewFailureCases == null
                        ? List.of()
                        : List.copyOf(initialNewFailureCases);
        newFailureCases =
                newFailureCases == null ? List.of() : List.copyOf(newFailureCases);
        resolvedFailureCases =
                resolvedFailureCases == null ? List.of() : List.copyOf(resolvedFailureCases);
        regressionRechecks =
                regressionRechecks == null ? List.of() : List.copyOf(regressionRechecks);
        confirmedRegressionCases =
                confirmedRegressionCases == null
                        ? List.of()
                        : List.copyOf(confirmedRegressionCases);
        unstableRecheckCases =
                unstableRecheckCases == null
                        ? List.of()
                        : List.copyOf(unstableRecheckCases);
    }
}
