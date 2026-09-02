package com.agentforge.infrastructure.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeEvaluationPropertiesTest {

    @Test
    void shouldRejectProductionIndexCollision() {
        KnowledgeHybridProperties hybrid = configuredHybrid();
        hybrid.getElasticsearch().setIndexName("agentforge-knowledge-v1");

        KnowledgeEvaluationProperties evaluation =
                new KnowledgeEvaluationProperties();
        evaluation.setEnabled(true);
        evaluation.setIndexName("agentforge-knowledge-v1");

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> evaluation.validate(hybrid));

        assertTrue(exception.getMessage().contains("must differ"));
    }

    @Test
    void shouldAcceptDedicatedEvaluationIndex() {
        KnowledgeHybridProperties hybrid = configuredHybrid();

        KnowledgeEvaluationProperties evaluation =
                new KnowledgeEvaluationProperties();
        evaluation.setEnabled(true);
        evaluation.setIndexName("agentforge-knowledge-eval-v070");

        assertDoesNotThrow(() -> evaluation.validate(hybrid));
        assertTrue(evaluation.isConfigured());
        assertEquals(List.of(5, 20, 60, 120), evaluation.getRrfKValues());
    }

    @Test
    void shouldRejectDuplicateRrfKValues() {
        KnowledgeHybridProperties hybrid = configuredHybrid();

        KnowledgeEvaluationProperties evaluation =
                new KnowledgeEvaluationProperties();
        evaluation.setEnabled(true);
        evaluation.setRrfKValues(List.of(20, 20, 60));

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> evaluation.validate(hybrid));

        assertTrue(exception.getMessage().contains("unique"));
    }

    private static KnowledgeHybridProperties configuredHybrid() {
        KnowledgeHybridProperties hybrid = new KnowledgeHybridProperties();
        hybrid.setEnabled(true);
        hybrid.getEmbedding().setApiKey("test-key");
        hybrid.getElasticsearch().setBaseUrl("http://localhost:9200");
        hybrid.getElasticsearch().setIndexName("agentforge-knowledge-v1");
        return hybrid;
    }
}
