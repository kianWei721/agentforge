package com.agentforge.rag;

import java.util.List;

public record KnowledgeChannelStrategyConfirmationReport(
        String selectionPolicy,
        String decisionBoundary,
        String productionVariant,
        int productionRrfK,
        boolean productionOnLeadingFrontier,
        boolean uniqueLeader,
        String leadingVariant,
        List<String> leadingVariants,
        List<String> leadingQueryModes,
        List<Integer> leadingRrfKValues,
        double leadingRecallAt1,
        double leadingRecallAt3,
        double leadingRecallAt5,
        double leadingMeanReciprocalRank,
        double productionRecallAt1,
        double productionRecallAt3,
        double productionRecallAt5,
        double productionMeanReciprocalRank,
        double recallAt1Delta,
        double recallAt3Delta,
        double recallAt5Delta,
        double meanReciprocalRankDelta) {

    public KnowledgeChannelStrategyConfirmationReport {
        leadingVariants =
                leadingVariants == null ? List.of() : List.copyOf(leadingVariants);
        leadingQueryModes =
                leadingQueryModes == null ? List.of() : List.copyOf(leadingQueryModes);
        leadingRrfKValues =
                leadingRrfKValues == null ? List.of() : List.copyOf(leadingRrfKValues);
    }
}
