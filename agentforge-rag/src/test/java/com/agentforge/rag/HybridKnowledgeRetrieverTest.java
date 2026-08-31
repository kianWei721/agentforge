package com.agentforge.rag;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class HybridKnowledgeRetrieverTest {

    @Test
    void shouldFuseLexicalAndVectorRanksWithStableCitation() {
        EmbeddingProvider embeddingProvider =
                new EmbeddingProvider() {
                    @Override
                    public List<float[]> embed(List<String> texts) {
                        return texts.stream().map(ignored -> new float[] {1f, 0f, 0f}).toList();
                    }

                    @Override
                    public String model() {
                        return "test-embedding";
                    }

                    @Override
                    public int dimensions() {
                        return 3;
                    }
                };

        KnowledgeBackendHit shared =
                new KnowledgeBackendHit(
                        "doc-shared",
                        "chunk-shared",
                        0,
                        "Offline troubleshooting",
                        KnowledgeSourceType.TROUBLESHOOTING,
                        "test://shared",
                        "Check power and SIM state.",
                        10d);
        KnowledgeBackendHit lexicalOnly =
                new KnowledgeBackendHit(
                        "doc-lex",
                        "chunk-lex",
                        0,
                        "Exact wording",
                        KnowledgeSourceType.MANUAL,
                        "test://lex",
                        "Exact keyword match.",
                        20d);
        KnowledgeBackendHit vectorOnly =
                new KnowledgeBackendHit(
                        "doc-vector",
                        "chunk-vector",
                        0,
                        "Semantic match",
                        KnowledgeSourceType.SOP,
                        "test://vector",
                        "Semantically related procedure.",
                        30d);

        KnowledgeSearchBackend backend =
                new KnowledgeSearchBackend() {
                    @Override
                    public void recreateIndex(int dimensions) {}

                    @Override
                    public void indexBatch(List<KnowledgeIndexedChunk> chunks) {}

                    @Override
                    public void refresh() {}

                    @Override
                    public List<KnowledgeBackendHit> searchLexical(String query, int limit) {
                        return List.of(shared, lexicalOnly);
                    }

                    @Override
                    public List<KnowledgeBackendHit> searchVector(float[] queryVector, int limit) {
                        return List.of(shared, vectorOnly);
                    }

                    @Override
                    public String indexName() {
                        return "test";
                    }
                };

        KnowledgeRetriever retriever =
                new HybridKnowledgeRetriever(embeddingProvider, backend, 4, 60);

        List<KnowledgeSearchHit> hits = retriever.search("设备离线怎么排查", 3);

        assertEquals(3, hits.size());
        assertEquals("doc-shared", hits.get(0).documentId());
        assertEquals("KB:doc-shared#0", hits.get(0).citationId());
        assertEquals("HYBRID_ES_RRF_V1", retriever.mode());
    }
}
