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
        for (int i = 0; i < 10; i++) {
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
        for (int i = 0; i < 16; i++) {
            cases.add(
                    new KnowledgeEvaluationCase(
                            "answer-" + i,
                            "test",
                            "query " + i,
                            List.of("doc-0")));
        }
        cases.add(
                new KnowledgeEvaluationCase(
                        "no-answer-1",
                        "test",
                        "unknown 1",
                        List.of()));
        cases.add(
                new KnowledgeEvaluationCase(
                        "no-answer-2",
                        "test",
                        "unknown 2",
                        List.of()));
        cases.add(
                new KnowledgeEvaluationCase(
                        "no-answer-3",
                        "test",
                        "unknown 3",
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
