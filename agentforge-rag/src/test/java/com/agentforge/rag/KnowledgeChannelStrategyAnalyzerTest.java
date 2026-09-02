package com.agentforge.rag;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeChannelStrategyAnalyzerTest {

    @Test
    void shouldPreferRecallAt5BeforeMrrAndRecallAt1() {
        KnowledgeCandidateEvaluationVariantReport production =
                variant(
                        "HYBRID_RRF_K60_REWRITE",
                        "REWRITE",
                        60,
                        0.95,
                        0.95,
                        0.95,
                        0.99);
        KnowledgeCandidateEvaluationVariantReport candidate =
                variant(
                        "HYBRID_RRF_K5_BM25_REWRITE_VECTOR_ORIGINAL",
                        "BM25_REWRITE_VECTOR_ORIGINAL",
                        5,
                        0.80,
                        0.90,
                        1.00,
                        0.85);

        KnowledgeChannelStrategyConfirmationReport report =
                KnowledgeChannelStrategyAnalyzer.analyze(
                        List.of(production, candidate),
                        60,
                        production.variant());

        assertTrue(report.uniqueLeader());
        assertEquals(candidate.variant(), report.leadingVariant());
        assertFalse(report.productionOnLeadingFrontier());
        assertEquals(0.05, report.recallAt5Delta(), 1.0e-9);
    }

    @Test
    void shouldKeepAllExactMetricTiesOnLeadingFrontier() {
        KnowledgeCandidateEvaluationVariantReport first =
                variant(
                        "HYBRID_RRF_K5_ORIGINAL",
                        "ORIGINAL",
                        5,
                        0.8,
                        0.9,
                        1.0,
                        0.85);
        KnowledgeCandidateEvaluationVariantReport second =
                variant(
                        "HYBRID_RRF_K20_BM25_REWRITE_VECTOR_ORIGINAL",
                        "BM25_REWRITE_VECTOR_ORIGINAL",
                        20,
                        0.8,
                        0.9,
                        1.0,
                        0.85);

        KnowledgeChannelStrategyConfirmationReport report =
                KnowledgeChannelStrategyAnalyzer.analyze(
                        List.of(first, second),
                        5,
                        first.variant());

        assertFalse(report.uniqueLeader());
        assertNull(report.leadingVariant());
        assertEquals(List.of(first.variant(), second.variant()), report.leadingVariants());
        assertTrue(report.productionOnLeadingFrontier());
    }

    private static KnowledgeCandidateEvaluationVariantReport variant(
            String name,
            String queryMode,
            int k,
            double recallAt1,
            double recallAt3,
            double recallAt5,
            double mrr) {
        return new KnowledgeCandidateEvaluationVariantReport(
                name,
                queryMode,
                "HYBRID_RRF",
                k,
                28,
                recallAt1,
                recallAt3,
                recallAt5,
                mrr,
                List.of());
    }
}
