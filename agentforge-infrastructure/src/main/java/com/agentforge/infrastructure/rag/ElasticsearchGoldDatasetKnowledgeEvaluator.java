package com.agentforge.infrastructure.rag;

import com.agentforge.infrastructure.config.KnowledgeAnswerabilityProperties;
import com.agentforge.infrastructure.config.KnowledgeEvaluationProperties;
import com.agentforge.infrastructure.config.KnowledgeHybridProperties;
import com.agentforge.infrastructure.config.KnowledgeRerankProperties;
import com.agentforge.rag.EmbeddingProvider;
import com.agentforge.rag.HybridKnowledgeRetriever;
import com.agentforge.rag.KnowledgeAnswerabilityJudge;
import com.agentforge.rag.KnowledgeCandidate;
import com.agentforge.rag.KnowledgeEvaluationCase;
import com.agentforge.rag.KnowledgeEvaluationCaseResult;
import com.agentforge.rag.KnowledgeEvaluationDataset;
import com.agentforge.rag.KnowledgeEvaluationDocument;
import com.agentforge.rag.KnowledgeEvaluationReport;
import com.agentforge.rag.KnowledgeEvaluationVariantReport;
import com.agentforge.rag.KnowledgeEvaluator;
import com.agentforge.rag.KnowledgeIndexedChunk;
import com.agentforge.rag.KnowledgeQueryRewriter;
import com.agentforge.rag.KnowledgeReranker;
import com.agentforge.rag.KnowledgeRetriever;
import com.agentforge.rag.KnowledgeSearchBackend;
import com.agentforge.rag.KnowledgeSearchHit;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

public class ElasticsearchGoldDatasetKnowledgeEvaluator implements KnowledgeEvaluator {

    private final EmbeddingProvider embeddingProvider;
    private final KnowledgeSearchBackend backend;
    private final KnowledgeHybridProperties hybridProperties;
    private final KnowledgeRerankProperties rerankProperties;
    private final KnowledgeAnswerabilityProperties answerabilityProperties;
    private final KnowledgeEvaluationProperties evaluationProperties;
    private final KnowledgeQueryRewriter queryRewriter;
    private final KnowledgeReranker reranker;
    private final KnowledgeAnswerabilityJudge answerabilityJudge;
    private final KnowledgeEvaluationDataset dataset;

    public ElasticsearchGoldDatasetKnowledgeEvaluator(
            EmbeddingProvider embeddingProvider,
            KnowledgeHybridProperties hybridProperties,
            KnowledgeRerankProperties rerankProperties,
            KnowledgeAnswerabilityProperties answerabilityProperties,
            KnowledgeEvaluationProperties evaluationProperties,
            KnowledgeQueryRewriter queryRewriter,
            KnowledgeReranker reranker,
            KnowledgeAnswerabilityJudge answerabilityJudge,
            ResourceLoader resourceLoader,
            ObjectMapper objectMapper) {
        this.embeddingProvider = embeddingProvider;
        this.hybridProperties = hybridProperties;
        this.rerankProperties = rerankProperties;
        this.answerabilityProperties = answerabilityProperties;
        this.evaluationProperties = evaluationProperties;
        this.queryRewriter = queryRewriter;
        this.reranker = reranker;
        this.answerabilityJudge = answerabilityJudge;

        evaluationProperties.validate(hybridProperties);
        this.dataset =
                loadDataset(
                        resourceLoader,
                        objectMapper,
                        evaluationProperties.getDatasetResource());
        validateDataset(dataset);

        KnowledgeHybridProperties.Elasticsearch evalEs =
                copyElasticsearch(
                        hybridProperties.getElasticsearch(),
                        evaluationProperties.getIndexName());
        HttpClient client =
                HttpClient.newBuilder()
                        .connectTimeout(evalEs.getConnectTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        this.backend =
                new ElasticsearchKnowledgeSearchBackend(
                        client,
                        objectMapper,
                        evalEs);
    }

    @Override
    public KnowledgeEvaluationReport run() {
        long started = System.nanoTime();
        indexDataset();

        KnowledgeEvaluationVariantReport baseline =
                evaluateVariant("HYBRID_BASELINE", null);
        KnowledgeEvaluationVariantReport rewrite =
                evaluateVariant("HYBRID_REWRITE", queryRewriter);

        return new KnowledgeEvaluationReport(
                dataset.version(),
                backend.indexName(),
                dataset.documents().size(),
                dataset.cases().size(),
                baseline,
                rewrite,
                rewrite.recallAt1() - baseline.recallAt1(),
                rewrite.recallAt3() - baseline.recallAt3(),
                rewrite.recallAt5() - baseline.recallAt5(),
                rewrite.meanReciprocalRank() - baseline.meanReciprocalRank(),
                rewrite.noAnswerAccuracy() - baseline.noAnswerAccuracy(),
                (System.nanoTime() - started) / 1_000_000L);
    }

    private void indexDataset() {
        List<KnowledgeCandidate> candidates =
                dataset.documents().stream()
                        .map(
                                document ->
                                        new KnowledgeCandidate(
                                                document.id(),
                                                document.id() + "#0",
                                                0,
                                                document.title(),
                                                document.sourceType(),
                                                document.sourceRef(),
                                                document.content()))
                        .toList();

        List<KnowledgeIndexedChunk> indexed = new ArrayList<>(candidates.size());
        int batchSize = hybridProperties.getEmbedding().getBatchSize();
        for (int start = 0; start < candidates.size(); start += batchSize) {
            int end = Math.min(candidates.size(), start + batchSize);
            List<KnowledgeCandidate> batch = candidates.subList(start, end);
            List<String> texts =
                    batch.stream()
                            .map(candidate -> candidate.title() + "\n\n" + candidate.content())
                            .toList();
            List<float[]> vectors = embeddingProvider.embed(texts);
            if (vectors.size() != batch.size()) {
                throw new IllegalStateException(
                        "evaluation embedding count mismatch: expected="
                                + batch.size()
                                + ", actual="
                                + vectors.size());
            }
            for (int i = 0; i < batch.size(); i++) {
                float[] vector = vectors.get(i);
                if (vector.length != embeddingProvider.dimensions()) {
                    throw new IllegalStateException(
                            "evaluation embedding dimension mismatch: expected="
                                    + embeddingProvider.dimensions()
                                    + ", actual="
                                    + vector.length);
                }
                indexed.add(new KnowledgeIndexedChunk(batch.get(i), vector));
            }
        }

        // The evaluation corpus is bounded and isolated from the production index.
        // Precompute all embeddings before recreating the evaluation index so provider
        // failures never destroy a previously inspectable evaluation index.
        backend.recreateIndex(embeddingProvider.dimensions());
        backend.indexBatch(indexed);
        backend.refresh();
    }

    private KnowledgeEvaluationVariantReport evaluateVariant(
            String variant,
            KnowledgeQueryRewriter rewriter) {
        KnowledgeRetriever retriever =
                new HybridKnowledgeRetriever(
                        embeddingProvider,
                        backend,
                        hybridProperties.getCandidateMultiplier(),
                        hybridProperties.getRrfK(),
                        hybridProperties.isRelevanceGateEnabled(),
                        hybridProperties.getMinVectorSimilarity(),
                        rewriter,
                        reranker,
                        rerankProperties.getCandidateLimit(),
                        answerabilityJudge,
                        answerabilityProperties.getCandidateLimit());

        List<KnowledgeEvaluationCaseResult> results =
                dataset.cases().stream()
                        .map(testCase -> evaluateCase(retriever, testCase))
                        .toList();

        int answerableCases = 0;
        int noAnswerCases = 0;
        int hitAt1 = 0;
        int hitAt3 = 0;
        int hitAt5 = 0;
        int correctNoAnswer = 0;
        int passed = 0;
        double reciprocalRankSum = 0d;

        for (KnowledgeEvaluationCaseResult result : results) {
            if (result.noAnswerExpected()) {
                noAnswerCases++;
                if (result.noAnswerActual()) {
                    correctNoAnswer++;
                }
            } else {
                answerableCases++;
                if (result.hitAt1()) {
                    hitAt1++;
                }
                if (result.hitAt3()) {
                    hitAt3++;
                }
                if (result.hitAt5()) {
                    hitAt5++;
                }
                reciprocalRankSum += result.reciprocalRank();
            }
            if (result.passed()) {
                passed++;
            }
        }

        return new KnowledgeEvaluationVariantReport(
                variant,
                retriever.mode(),
                answerableCases,
                noAnswerCases,
                ratio(hitAt1, answerableCases),
                ratio(hitAt3, answerableCases),
                ratio(hitAt5, answerableCases),
                answerableCases == 0 ? 0d : reciprocalRankSum / answerableCases,
                ratio(correctNoAnswer, noAnswerCases),
                ratio(passed, results.size()),
                results);
    }

    private KnowledgeEvaluationCaseResult evaluateCase(
            KnowledgeRetriever retriever,
            KnowledgeEvaluationCase testCase) {
        List<KnowledgeSearchHit> hits =
                retriever.searchDetailed(
                                testCase.query(),
                                evaluationProperties.getTopK())
                        .hits();

        List<String> actualDocumentIds =
                hits.stream()
                        .map(KnowledgeSearchHit::documentId)
                        .toList();
        Set<String> expected =
                new LinkedHashSet<>(testCase.expectedDocumentIds());

        Integer rank = null;
        for (int i = 0; i < actualDocumentIds.size(); i++) {
            if (expected.contains(actualDocumentIds.get(i))) {
                rank = i + 1;
                break;
            }
        }

        boolean noAnswerActual = hits.isEmpty();
        boolean noAnswerExpected = testCase.noAnswerExpected();
        boolean hitAt1 = rank != null && rank <= 1;
        boolean hitAt3 = rank != null && rank <= 3;
        boolean hitAt5 = rank != null && rank <= 5;
        double reciprocalRank = rank == null ? 0d : 1.0d / rank;
        boolean passed =
                noAnswerExpected
                        ? noAnswerActual
                        : rank != null && rank <= evaluationProperties.getTopK();

        return new KnowledgeEvaluationCaseResult(
                testCase.name(),
                testCase.category(),
                testCase.query(),
                testCase.expectedDocumentIds(),
                actualDocumentIds,
                noAnswerExpected,
                noAnswerActual,
                rank,
                reciprocalRank,
                hitAt1,
                hitAt3,
                hitAt5,
                passed);
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0d : (double) numerator / denominator;
    }

    private static KnowledgeEvaluationDataset loadDataset(
            ResourceLoader resourceLoader,
            ObjectMapper objectMapper,
            String location) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "Knowledge evaluation dataset not found: " + location);
        }
        try (InputStream input = resource.getInputStream()) {
            return objectMapper.readValue(input, KnowledgeEvaluationDataset.class);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Failed to load knowledge evaluation dataset: " + location,
                    exception);
        }
    }

    static void validateDataset(KnowledgeEvaluationDataset dataset) {
        if (dataset == null
                || dataset.version() == null
                || dataset.version().isBlank()) {
            throw new IllegalStateException("Evaluation dataset version is required.");
        }
        if (dataset.documents().size() < 5) {
            throw new IllegalStateException(
                    "Evaluation dataset must contain at least 5 documents.");
        }
        if (dataset.cases().size() < 10) {
            throw new IllegalStateException(
                    "Evaluation dataset must contain at least 10 cases.");
        }

        Set<String> documentIds = new HashSet<>();
        for (KnowledgeEvaluationDocument document : dataset.documents()) {
            if (document == null
                    || document.id() == null
                    || document.id().isBlank()
                    || document.title() == null
                    || document.title().isBlank()
                    || document.sourceType() == null
                    || document.content() == null
                    || document.content().isBlank()) {
                throw new IllegalStateException(
                        "Evaluation documents require id/title/sourceType/content.");
            }
            if (!documentIds.add(document.id())) {
                throw new IllegalStateException(
                        "Duplicate evaluation document id: " + document.id());
            }
        }

        Set<String> caseNames = new HashSet<>();
        int answerable = 0;
        int noAnswer = 0;
        for (KnowledgeEvaluationCase testCase : dataset.cases()) {
            if (testCase == null
                    || testCase.name() == null
                    || testCase.name().isBlank()
                    || testCase.query() == null
                    || testCase.query().isBlank()) {
                throw new IllegalStateException(
                        "Evaluation cases require name/query.");
            }
            if (!caseNames.add(testCase.name())) {
                throw new IllegalStateException(
                        "Duplicate evaluation case name: " + testCase.name());
            }
            if (testCase.noAnswerExpected()) {
                noAnswer++;
            } else {
                answerable++;
                for (String expected : testCase.expectedDocumentIds()) {
                    if (!documentIds.contains(expected)) {
                        throw new IllegalStateException(
                                "Evaluation case "
                                        + testCase.name()
                                        + " references missing document "
                                        + expected);
                    }
                }
            }
        }

        if (answerable == 0 || noAnswer == 0) {
            throw new IllegalStateException(
                    "Evaluation dataset must contain answerable and no-answer cases.");
        }
    }

    private static KnowledgeHybridProperties.Elasticsearch copyElasticsearch(
            KnowledgeHybridProperties.Elasticsearch source,
            String indexName) {
        KnowledgeHybridProperties.Elasticsearch copy =
                new KnowledgeHybridProperties.Elasticsearch();
        copy.setBaseUrl(source.getBaseUrl());
        copy.setIndexName(indexName);
        copy.setUsername(source.getUsername());
        copy.setPassword(source.getPassword());
        copy.setApiKey(source.getApiKey());
        copy.setConnectTimeout(source.getConnectTimeout());
        copy.setRequestTimeout(source.getRequestTimeout());
        return copy;
    }
}
