package com.agentforge.infrastructure.rag;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.rag.KnowledgeEvaluationCase;
import com.agentforge.rag.KnowledgeEvaluationDataset;
import com.agentforge.rag.KnowledgeEvaluationDocument;
import com.agentforge.rag.KnowledgeSourceType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ElasticsearchGoldDatasetKnowledgeEvaluatorTest {

    @Test
    void shouldRejectCaseThatReferencesMissingDocument() {
        List<KnowledgeEvaluationDocument> documents = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            documents.add(
                    new KnowledgeEvaluationDocument(
                            "doc-" + i,
                            "Title " + i,
                            KnowledgeSourceType.TEST,
                            "eval://" + i,
                            "Content " + i));
        }

        List<KnowledgeEvaluationCase> cases = new ArrayList<>();
        cases.add(
                new KnowledgeEvaluationCase(
                        "bad-reference",
                        "test",
                        "query",
                        List.of("missing-doc")));
        for (int i = 0; i < 8; i++) {
            cases.add(
                    new KnowledgeEvaluationCase(
                            "answer-" + i,
                            "test",
                            "query " + i,
                            List.of("doc-0")));
        }
        cases.add(
                new KnowledgeEvaluationCase(
                        "no-answer",
                        "test",
                        "unknown",
                        List.of()));

        KnowledgeEvaluationDataset dataset =
                new KnowledgeEvaluationDataset(
                        "test-v1",
                        "test",
                        documents,
                        cases);

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                ElasticsearchGoldDatasetKnowledgeEvaluator
                                        .validateDataset(dataset));

        assertTrue(exception.getMessage().contains("missing document"));
    }
}
