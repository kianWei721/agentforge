package com.agentforge.rag;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

public final class KnowledgeChannelStrategyAnalyzer {

    public static final String SELECTION_POLICY =
            "LEXICOGRAPHIC_RECALL5_RECALL3_MRR_RECALL1";
    public static final String DECISION_BOUNDARY =
            "CALIBRATION_ONLY_NO_AUTO_PRODUCTION_CHANGE";

    private KnowledgeChannelStrategyAnalyzer() {}

    public static KnowledgeChannelStrategyConfirmationReport analyze(
            List<KnowledgeCandidateEvaluationVariantReport> variants,
            int productionRrfK,
            String productionVariant) {
        List<KnowledgeCandidateEvaluationVariantReport> hybrid =
                variants == null
                        ? List.of()
                        : variants.stream()
                                .filter(v -> "HYBRID_RRF".equals(v.retrievalStrategy()))
                                .toList();
        if (hybrid.isEmpty()) {
            throw new IllegalArgumentException("hybrid RRF variants are required");
        }

        KnowledgeCandidateEvaluationVariantReport production =
                hybrid.stream()
                        .filter(v -> productionVariant.equals(v.variant()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "production variant not found: "
                                                        + productionVariant));

        Comparator<KnowledgeCandidateEvaluationVariantReport> quality =
                Comparator.comparingDouble(
                                KnowledgeCandidateEvaluationVariantReport::recallAt5)
                        .thenComparingDouble(
                                KnowledgeCandidateEvaluationVariantReport::recallAt3)
                        .thenComparingDouble(
                                KnowledgeCandidateEvaluationVariantReport::meanReciprocalRank)
                        .thenComparingDouble(
                                KnowledgeCandidateEvaluationVariantReport::recallAt1);

        KnowledgeCandidateEvaluationVariantReport best =
                hybrid.stream().max(quality).orElseThrow();

        List<KnowledgeCandidateEvaluationVariantReport> leaders =
                hybrid.stream()
                        .filter(v -> sameQuality(v, best))
                        .toList();

        List<String> leadingVariants =
                leaders.stream()
                        .map(KnowledgeCandidateEvaluationVariantReport::variant)
                        .toList();
        List<String> leadingQueryModes =
                new LinkedHashSet<>(
                                leaders.stream()
                                        .map(
                                                KnowledgeCandidateEvaluationVariantReport
                                                        ::queryMode)
                                        .toList())
                        .stream()
                        .toList();
        List<Integer> leadingRrfKValues =
                new LinkedHashSet<>(
                                leaders.stream()
                                        .map(KnowledgeCandidateEvaluationVariantReport::rrfK)
                                        .toList())
                        .stream()
                        .toList();

        boolean uniqueLeader = leaders.size() == 1;
        String leadingVariant = uniqueLeader ? leaders.get(0).variant() : null;

        return new KnowledgeChannelStrategyConfirmationReport(
                SELECTION_POLICY,
                DECISION_BOUNDARY,
                productionVariant,
                productionRrfK,
                leadingVariants.contains(productionVariant),
                uniqueLeader,
                leadingVariant,
                leadingVariants,
                leadingQueryModes,
                leadingRrfKValues,
                best.recallAt1(),
                best.recallAt3(),
                best.recallAt5(),
                best.meanReciprocalRank(),
                production.recallAt1(),
                production.recallAt3(),
                production.recallAt5(),
                production.meanReciprocalRank(),
                best.recallAt1() - production.recallAt1(),
                best.recallAt3() - production.recallAt3(),
                best.recallAt5() - production.recallAt5(),
                best.meanReciprocalRank() - production.meanReciprocalRank());
    }

    private static boolean sameQuality(
            KnowledgeCandidateEvaluationVariantReport left,
            KnowledgeCandidateEvaluationVariantReport right) {
        return Double.compare(left.recallAt5(), right.recallAt5()) == 0
                && Double.compare(left.recallAt3(), right.recallAt3()) == 0
                && Double.compare(
                                left.meanReciprocalRank(),
                                right.meanReciprocalRank())
                        == 0
                && Double.compare(left.recallAt1(), right.recallAt1()) == 0;
    }
}
