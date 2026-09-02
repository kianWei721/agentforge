package com.agentforge.rag;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class RrfFusionTest {

    @Test
    void shouldRewardCrossChannelConsensus() {
        KnowledgeBackendHit lexicalOnly = hit("lexical-only", 10.0d);
        KnowledgeBackendHit consensus = hit("consensus", 9.0d);

        List<KnowledgeSearchHit> fused =
                RrfFusion.fuse(
                        List.of(lexicalOnly, consensus),
                        List.of(consensus),
                        60,
                        score -> score);

        assertEquals("consensus", fused.get(0).documentId());
        assertEquals("HYBRID", fused.get(0).matchType());
        assertEquals("LEXICAL", fused.get(1).matchType());
    }

    @Test
    void kShouldControlRankDecayWithoutChangingChannelScores() {
        KnowledgeBackendHit first = hit("first", 1.0d);
        KnowledgeBackendHit second = hit("second", 0.9d);
        KnowledgeBackendHit tenth = hit("tenth", 0.8d);

        List<KnowledgeBackendHit> lexical =
                List.of(
                        first,
                        second,
                        hit("3", 0.7d),
                        hit("4", 0.6d),
                        hit("5", 0.5d),
                        hit("6", 0.4d),
                        hit("7", 0.3d),
                        hit("8", 0.2d),
                        hit("9", 0.1d),
                        tenth);

        double headHeavyGap =
                RrfFusion.fuse(lexical, List.of(), 5, score -> score).get(0).score()
                        - RrfFusion.fuse(lexical, List.of(), 5, score -> score).get(9).score();
        double flatGap =
                RrfFusion.fuse(lexical, List.of(), 120, score -> score).get(0).score()
                        - RrfFusion.fuse(lexical, List.of(), 120, score -> score).get(9).score();

        assertTrue(headHeavyGap > flatGap);
    }

    private static KnowledgeBackendHit hit(String id, double score) {
        return new KnowledgeBackendHit(
                id,
                id + "#0",
                0,
                id,
                KnowledgeSourceType.TEST,
                "eval://" + id,
                "content",
                score);
    }
}
