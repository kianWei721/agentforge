package com.agentforge.rag;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class KnowledgeEndToEndConfirmationAnalyzer {

    public static final String DECISION_BOUNDARY =
            "EVALUATION_PROMOTION_GATE_MANUAL_PRODUCTION_CHANGE_REQUIRED";

    private KnowledgeEndToEndConfirmationAnalyzer() {}

    public static KnowledgeEndToEndConfirmationReport analyze(
            KnowledgeChannelStrategyConfirmationReport candidateConfirmation,
            KnowledgeCandidateEvaluationVariantReport candidateLeader,
            KnowledgeEvaluationVariantReport control,
            KnowledgeEvaluationVariantReport challenger,
            int productionRrfK,
            List<String> initialNewFailureCases,
            List<KnowledgeRegressionRecheckCaseReport> rechecks) {
        if (candidateConfirmation == null
                || candidateLeader == null
                || control == null
                || challenger == null) {
            throw new IllegalArgumentException("end-to-end confirmation inputs are required");
        }

        boolean candidateLeaderConfirmed =
                candidateConfirmation.uniqueLeader()
                        && candidateConfirmation.leadingVariant() != null
                        && candidateConfirmation.leadingVariant().equals(candidateLeader.variant());

        Set<String> controlFailures = failedCases(control);
        Set<String> challengerFailures = failedCases(challenger);

        List<String> newFailures =
                challengerFailures.stream()
                        .filter(name -> !controlFailures.contains(name))
                        .toList();
        List<String> resolvedFailures =
                controlFailures.stream()
                        .filter(name -> !challengerFailures.contains(name))
                        .toList();

        List<KnowledgeRegressionRecheckCaseReport> safeRechecks =
                rechecks == null ? List.of() : List.copyOf(rechecks);
        List<String> confirmedRegressions =
                safeRechecks.stream()
                        .filter(recheck -> "CONFIRMED_REGRESSION".equals(recheck.status()))
                        .map(KnowledgeRegressionRecheckCaseReport::caseName)
                        .toList();
        List<String> unstableRechecks =
                safeRechecks.stream()
                        .filter(recheck -> "UNSTABLE".equals(recheck.status()))
                        .map(KnowledgeRegressionRecheckCaseReport::caseName)
                        .toList();

        boolean metricsNonRegressing =
                challenger.recallAt1() >= control.recallAt1()
                        && challenger.recallAt3() >= control.recallAt3()
                        && challenger.recallAt5() >= control.recallAt5()
                        && challenger.meanReciprocalRank() >= control.meanReciprocalRank()
                        && challenger.noAnswerAccuracy() >= control.noAnswerAccuracy()
                        && challenger.overallAccuracy() >= control.overallAccuracy();

        boolean promotionEligible =
                candidateLeaderConfirmed
                        && confirmedRegressions.isEmpty()
                        && unstableRechecks.isEmpty()
                        && newFailures.isEmpty()
                        && metricsNonRegressing;

        String decision;
        if (promotionEligible) {
            decision = "PROMOTION_ELIGIBLE";
        } else if (!unstableRechecks.isEmpty()) {
            decision = "HOLD_FOR_STABILITY";
        } else {
            decision = "HOLD_PRODUCTION";
        }

        return new KnowledgeEndToEndConfirmationReport(
                DECISION_BOUNDARY,
                decision,
                promotionEligible,
                candidateLeaderConfirmed,
                candidateLeader.variant(),
                candidateLeader.queryMode(),
                candidateLeader.rrfK(),
                productionRrfK,
                control,
                challenger,
                initialNewFailureCases == null ? List.of() : initialNewFailureCases,
                newFailures,
                resolvedFailures,
                safeRechecks,
                confirmedRegressions,
                unstableRechecks,
                challenger.recallAt1() - control.recallAt1(),
                challenger.recallAt3() - control.recallAt3(),
                challenger.recallAt5() - control.recallAt5(),
                challenger.meanReciprocalRank() - control.meanReciprocalRank(),
                challenger.noAnswerAccuracy() - control.noAnswerAccuracy(),
                challenger.overallAccuracy() - control.overallAccuracy());
    }

    public static List<String> newFailureCases(
            KnowledgeEvaluationVariantReport control,
            KnowledgeEvaluationVariantReport challenger) {
        Set<String> controlFailures = failedCases(control);
        return failedCases(challenger).stream()
                .filter(name -> !controlFailures.contains(name))
                .toList();
    }

    private static Set<String> failedCases(KnowledgeEvaluationVariantReport report) {
        Set<String> failures = new LinkedHashSet<>();
        for (KnowledgeEvaluationCaseResult result : report.cases()) {
            if (!result.passed()) {
                failures.add(result.name());
            }
        }
        return failures;
    }
}
