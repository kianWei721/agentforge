package com.agentforge.rag;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeEndToEndConfirmationAnalyzerTest {

    @Test
    void shouldPromoteWhenNoRegressionRemainsAfterGate() {
        KnowledgeCandidateEvaluationVariantReport leader =
                candidate("HYBRID_RRF_K5_ORIGINAL", "ORIGINAL", 5);
        KnowledgeChannelStrategyConfirmationReport candidateConfirmation =
                confirmation(leader.variant());

        KnowledgeEvaluationVariantReport control =
                report("CONTROL", 1, 1, 1, 1, 1, 1, List.of(result("a", true)));
        KnowledgeEvaluationVariantReport challenger =
                report("CHALLENGER", 1, 1, 1, 1, 1, 1, List.of(result("a", true)));

        KnowledgeEndToEndConfirmationReport confirmation =
                KnowledgeEndToEndConfirmationAnalyzer.analyze(
                        candidateConfirmation,
                        leader,
                        control,
                        challenger,
                        60,
                        List.of(),
                        List.of());

        assertTrue(confirmation.promotionEligible());
        assertEquals("PROMOTION_ELIGIBLE", confirmation.decision());
        assertTrue(confirmation.newFailureCases().isEmpty());
    }

    @Test
    void shouldAcceptClearedTransientRecheck() {
        KnowledgeCandidateEvaluationVariantReport leader =
                candidate("HYBRID_RRF_K5_ORIGINAL", "ORIGINAL", 5);
        KnowledgeChannelStrategyConfirmationReport candidateConfirmation =
                confirmation(leader.variant());

        KnowledgeEvaluationVariantReport control =
                report("CONTROL", 1, 1, 1, 1, 1, 1, List.of(result("a", true)));
        KnowledgeEvaluationVariantReport challenger =
                report("CHALLENGER", 1, 1, 1, 1, 1, 1, List.of(result("a", true)));

        KnowledgeRegressionRecheckCaseReport recheck =
                recheck("a", 3, 3, "CLEARED_TRANSIENT");

        KnowledgeEndToEndConfirmationReport confirmation =
                KnowledgeEndToEndConfirmationAnalyzer.analyze(
                        candidateConfirmation,
                        leader,
                        control,
                        challenger,
                        60,
                        List.of("a"),
                        List.of(recheck));

        assertTrue(confirmation.promotionEligible());
        assertEquals(List.of("a"), confirmation.initialNewFailureCases());
        assertTrue(confirmation.confirmedRegressionCases().isEmpty());
    }

    @Test
    void shouldHoldOnConfirmedRegression() {
        KnowledgeCandidateEvaluationVariantReport leader =
                candidate("HYBRID_RRF_K5_ORIGINAL", "ORIGINAL", 5);
        KnowledgeChannelStrategyConfirmationReport candidateConfirmation =
                confirmation(leader.variant());

        KnowledgeEvaluationVariantReport control =
                report("CONTROL", 1, 1, 1, 1, 1, 1, List.of(result("a", true)));
        KnowledgeEvaluationVariantReport challenger =
                report("CHALLENGER", 1, 1, 1, 1, 1, 1, List.of(result("a", true)));

        KnowledgeRegressionRecheckCaseReport recheck =
                recheck("a", 3, 0, "CONFIRMED_REGRESSION");

        KnowledgeEndToEndConfirmationReport confirmation =
                KnowledgeEndToEndConfirmationAnalyzer.analyze(
                        candidateConfirmation,
                        leader,
                        control,
                        challenger,
                        60,
                        List.of("a"),
                        List.of(recheck));

        assertFalse(confirmation.promotionEligible());
        assertEquals("HOLD_PRODUCTION", confirmation.decision());
        assertEquals(List.of("a"), confirmation.confirmedRegressionCases());
    }

    @Test
    void shouldHoldForStabilityWhenRecheckIsMixed() {
        KnowledgeCandidateEvaluationVariantReport leader =
                candidate("HYBRID_RRF_K5_ORIGINAL", "ORIGINAL", 5);
        KnowledgeChannelStrategyConfirmationReport candidateConfirmation =
                confirmation(leader.variant());

        KnowledgeEvaluationVariantReport control =
                report("CONTROL", 1, 1, 1, 1, 1, 1, List.of(result("a", true)));
        KnowledgeEvaluationVariantReport challenger =
                report("CHALLENGER", 1, 1, 1, 1, 1, 1, List.of(result("a", true)));

        KnowledgeRegressionRecheckCaseReport recheck =
                recheck("a", 3, 2, "UNSTABLE");

        KnowledgeEndToEndConfirmationReport confirmation =
                KnowledgeEndToEndConfirmationAnalyzer.analyze(
                        candidateConfirmation,
                        leader,
                        control,
                        challenger,
                        60,
                        List.of("a"),
                        List.of(recheck));

        assertFalse(confirmation.promotionEligible());
        assertEquals("HOLD_FOR_STABILITY", confirmation.decision());
        assertEquals(List.of("a"), confirmation.unstableRecheckCases());
    }

    private static KnowledgeRegressionRecheckCaseReport recheck(
            String name, int controlPasses, int challengerPasses, String status) {
        return new KnowledgeRegressionRecheckCaseReport(
                name,
                3,
                true,
                false,
                controlPasses,
                challengerPasses,
                status,
                List.of(),
                List.of());
    }

    private static KnowledgeCandidateEvaluationVariantReport candidate(
            String name, String mode, int k) {
        return new KnowledgeCandidateEvaluationVariantReport(
                name, mode, "HYBRID_RRF", k, 28, 1, 1, 1, 1, List.of());
    }

    private static KnowledgeChannelStrategyConfirmationReport confirmation(
            String leader) {
        return new KnowledgeChannelStrategyConfirmationReport(
                KnowledgeChannelStrategyAnalyzer.SELECTION_POLICY,
                KnowledgeChannelStrategyAnalyzer.DECISION_BOUNDARY,
                "HYBRID_RRF_K60_REWRITE",
                60,
                false,
                true,
                leader,
                List.of(leader),
                List.of("ORIGINAL"),
                List.of(5),
                1,
                1,
                1,
                1,
                0.8,
                0.8,
                0.9,
                0.8,
                0.2,
                0.2,
                0.1,
                0.2);
    }

    private static KnowledgeEvaluationVariantReport report(
            String variant,
            double r1,
            double r3,
            double r5,
            double mrr,
            double noAnswer,
            double overall,
            List<KnowledgeEvaluationCaseResult> cases) {
        return new KnowledgeEvaluationVariantReport(
                variant, "TEST", 1, 0, r1, r3, r5, mrr, noAnswer, overall, cases);
    }

    private static KnowledgeEvaluationCaseResult result(
            String name, boolean passed) {
        return new KnowledgeEvaluationCaseResult(
                name,
                "test",
                "query",
                List.of("doc"),
                passed ? List.of("doc") : List.of(),
                false,
                !passed,
                passed ? 1 : null,
                passed ? 1 : 0,
                passed,
                passed,
                passed,
                passed);
    }
}
