package com.agentforge.rag;

import java.util.ArrayList;
import java.util.List;

public class KnowledgeIndexService {

    private final KnowledgeRepository repository;
    private final EmbeddingProvider embeddingProvider;
    private final KnowledgeSearchBackend backend;
    private final int embeddingBatchSize;
    private final int maxCandidates;

    public KnowledgeIndexService(
            KnowledgeRepository repository,
            EmbeddingProvider embeddingProvider,
            KnowledgeSearchBackend backend,
            int embeddingBatchSize,
            int maxCandidates) {
        if (embeddingBatchSize < 1) {
            throw new IllegalArgumentException("embeddingBatchSize must be positive");
        }
        if (maxCandidates < 1) {
            throw new IllegalArgumentException("maxCandidates must be positive");
        }
        this.repository = repository;
        this.embeddingProvider = embeddingProvider;
        this.backend = backend;
        this.embeddingBatchSize = embeddingBatchSize;
        this.maxCandidates = maxCandidates;
    }

    public KnowledgeIndexReport rebuild() {
        long startedAt = System.nanoTime();
        List<KnowledgeCandidate> candidates = repository.findEnabledCandidates(maxCandidates);

        if (candidates.isEmpty()) {
            backend.recreateIndex(embeddingProvider.dimensions());
            backend.refresh();
            return report(startedAt, 0);
        }

        int firstEnd = Math.min(candidates.size(), embeddingBatchSize);
        List<KnowledgeIndexedChunk> firstBatch =
                embedBatch(candidates.subList(0, firstEnd));

        // Do not destroy the current derived index until the embedding provider has
        // successfully completed at least one real request. This protects rebuilds
        // from common credential/quota/model/configuration failures.
        backend.recreateIndex(embeddingProvider.dimensions());

        int indexed = 0;
        backend.indexBatch(firstBatch);
        indexed += firstBatch.size();

        for (int start = firstEnd; start < candidates.size(); start += embeddingBatchSize) {
            int end = Math.min(candidates.size(), start + embeddingBatchSize);
            List<KnowledgeIndexedChunk> indexedBatch =
                    embedBatch(candidates.subList(start, end));
            backend.indexBatch(indexedBatch);
            indexed += indexedBatch.size();
        }

        backend.refresh();
        return report(startedAt, indexed);
    }

    private List<KnowledgeIndexedChunk> embedBatch(List<KnowledgeCandidate> batch) {
        List<String> texts =
                batch.stream()
                        .map(KnowledgeIndexService::embeddingText)
                        .toList();
        List<float[]> vectors = embeddingProvider.embed(texts);
        if (vectors.size() != batch.size()) {
            throw new IllegalStateException(
                    "embedding count mismatch: expected="
                            + batch.size()
                            + ", actual="
                            + vectors.size());
        }

        List<KnowledgeIndexedChunk> indexedBatch = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            float[] vector = vectors.get(i);
            if (vector.length != embeddingProvider.dimensions()) {
                throw new IllegalStateException(
                        "embedding dimension mismatch: expected="
                                + embeddingProvider.dimensions()
                                + ", actual="
                                + vector.length);
            }
            indexedBatch.add(new KnowledgeIndexedChunk(batch.get(i), vector));
        }
        return List.copyOf(indexedBatch);
    }

    private KnowledgeIndexReport report(long startedAt, int indexed) {
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000L;
        return new KnowledgeIndexReport(
                backend.indexName(),
                embeddingProvider.model(),
                embeddingProvider.dimensions(),
                indexed,
                durationMs);
    }

    private static String embeddingText(KnowledgeCandidate candidate) {
        return candidate.title() + "\n\n" + candidate.content();
    }
}
