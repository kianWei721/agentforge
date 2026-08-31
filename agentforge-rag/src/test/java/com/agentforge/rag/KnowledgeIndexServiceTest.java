package com.agentforge.rag;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class KnowledgeIndexServiceTest {

    @Test
    void shouldEmbedAndIndexEveryEnabledCandidate() {
        KnowledgeRepository repository =
                new KnowledgeRepository() {
                    @Override
                    public KnowledgeDocument save(
                            KnowledgeDocument document, List<KnowledgeChunk> chunks) {
                        return document;
                    }

                    @Override
                    public Optional<KnowledgeDocument> findById(String id) {
                        return Optional.empty();
                    }

                    @Override
                    public List<KnowledgeDocument> findAll() {
                        return List.of();
                    }

                    @Override
                    public List<KnowledgeCandidate> findEnabledCandidates(int maxCandidates) {
                        return List.of(
                                candidate("doc-1", "chunk-1", 0),
                                candidate("doc-2", "chunk-2", 0));
                    }

                    @Override
                    public void deleteById(String id) {}
                };

        EmbeddingProvider embeddingProvider =
                new EmbeddingProvider() {
                    @Override
                    public List<float[]> embed(List<String> texts) {
                        return texts.stream().map(ignored -> new float[] {1f, 2f, 3f}).toList();
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

        List<KnowledgeIndexedChunk> indexed = new ArrayList<>();
        KnowledgeSearchBackend backend =
                new KnowledgeSearchBackend() {
                    @Override
                    public void recreateIndex(int dimensions) {
                        assertEquals(3, dimensions);
                    }

                    @Override
                    public void indexBatch(List<KnowledgeIndexedChunk> chunks) {
                        indexed.addAll(chunks);
                    }

                    @Override
                    public void refresh() {}

                    @Override
                    public List<KnowledgeBackendHit> searchLexical(String query, int limit) {
                        return List.of();
                    }

                    @Override
                    public List<KnowledgeBackendHit> searchVector(float[] queryVector, int limit) {
                        return List.of();
                    }

                    @Override
                    public String indexName() {
                        return "agentforge-knowledge-test";
                    }
                };

        KnowledgeIndexReport report =
                new KnowledgeIndexService(repository, embeddingProvider, backend, 1, 100)
                        .rebuild();

        assertEquals(2, indexed.size());
        assertEquals(2, report.indexedChunks());
        assertEquals("test-embedding", report.embeddingModel());
        assertEquals(3, report.embeddingDimensions());
    }

    private static KnowledgeCandidate candidate(
            String documentId, String chunkId, int chunkIndex) {
        return new KnowledgeCandidate(
                documentId,
                chunkId,
                chunkIndex,
                "Title " + documentId,
                KnowledgeSourceType.TEST,
                "test://" + documentId,
                "Knowledge content " + documentId);
    }
}
