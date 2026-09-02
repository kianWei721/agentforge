package com.agentforge.infrastructure.rag;

import com.agentforge.infrastructure.config.KnowledgeAnswerabilityProperties;
import com.agentforge.infrastructure.config.KnowledgeEvaluationProperties;
import com.agentforge.infrastructure.config.KnowledgeHybridProperties;
import com.agentforge.infrastructure.config.KnowledgeRerankProperties;
import com.agentforge.rag.EmbeddingProvider;
import com.agentforge.rag.HybridKnowledgeRetriever;
import com.agentforge.rag.KnowledgeAnswerabilityJudge;
import com.agentforge.rag.KnowledgeBackendHit;
import com.agentforge.rag.KnowledgeCandidate;
import com.agentforge.rag.KnowledgeCandidateBenchmarkReport;
import com.agentforge.rag.KnowledgeCandidateEvaluationCaseResult;
import com.agentforge.rag.KnowledgeCandidateEvaluationVariantReport;
import com.agentforge.rag.KnowledgeEvaluationCase;
import com.agentforge.rag.KnowledgeEvaluationCaseResult;
import com.agentforge.rag.KnowledgeEvaluationDataset;
import com.agentforge.rag.KnowledgeEvaluationDocument;
import com.agentforge.rag.KnowledgeEvaluationReport;
import com.agentforge.rag.KnowledgeEvaluationStageDiagnostic;
import com.agentforge.rag.KnowledgeEvaluationVariantReport;
import com.agentforge.rag.KnowledgeEvaluator;
import com.agentforge.rag.KnowledgeIndexedChunk;
import com.agentforge.rag.KnowledgeQueryRewriteResult;
import com.agentforge.rag.KnowledgeQueryRewriter;
import com.agentforge.rag.KnowledgeReranker;
import com.agentforge.rag.KnowledgeRetriever;
import com.agentforge.rag.KnowledgeSearchBackend;
import com.agentforge.rag.KnowledgeSearchHit;
import com.agentforge.rag.KnowledgeStageDiagnosticsReport;
import com.agentforge.rag.RrfFusion;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

        KnowledgeCandidateBenchmarkReport candidateBenchmark =
                evaluateCandidateBenchmark();

        KnowledgeEvaluationVariantReport baseline =
                evaluateVariant("HYBRID_BASELINE", null);
        KnowledgeEvaluationVariantReport rewrite =
                evaluateVariant("HYBRID_REWRITE", queryRewriter);

        KnowledgeStageDiagnosticsReport stageDiagnostics =
                evaluateStageDiagnostics(candidateBenchmark, baseline, rewrite);

        return new KnowledgeEvaluationReport(
                dataset.version(),
                backend.indexName(),
                dataset.documents().size(),
                dataset.cases().size(),
                candidateBenchmark,
                stageDiagnostics,
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

        // Evaluation is bounded and isolated. Finish all embeddings before
        // recreating the dedicated evaluation index.
        backend.recreateIndex(embeddingProvider.dimensions());
        backend.indexBatch(indexed);
        backend.refresh();
    }

    private KnowledgeCandidateBenchmarkReport evaluateCandidateBenchmark() {
        int candidateWindow =
                Math.min(
                        100,
                        Math.max(
                                20,
                                evaluationProperties.getTopK()
                                        * hybridProperties.getCandidateMultiplier()));

        Map<String, VariantAccumulator> variants = new LinkedHashMap<>();
        registerCandidateVariants(variants, "ORIGINAL");
        registerCandidateVariants(variants, "REWRITE");

        for (KnowledgeEvaluationCase testCase : dataset.cases()) {
            if (testCase.noAnswerExpected()) {
                continue;
            }

            CandidateChannels original =
                    candidateChannels(testCase.query(), candidateWindow);
            String rewrittenQuery = rewriteForEvaluation(testCase.query());
            CandidateChannels rewritten =
                    candidateChannels(rewrittenQuery, candidateWindow);

            evaluateCandidateChannels(
                    variants,
                    "ORIGINAL",
                    testCase,
                    original);
            evaluateCandidateChannels(
                    variants,
                    "REWRITE",
                    testCase,
                    rewritten);
        }

        List<KnowledgeCandidateEvaluationVariantReport> reports =
                variants.values().stream()
                        .map(VariantAccumulator::report)
                        .toList();

        return new KnowledgeCandidateBenchmarkReport(
                candidateWindow,
                evaluationProperties.getRrfKValues(),
                reports);
    }

    private void registerCandidateVariants(
            Map<String, VariantAccumulator> variants,
            String queryMode) {
        variants.put(
                "BM25_" + queryMode,
                new VariantAccumulator(
                        "BM25_" + queryMode,
                        queryMode,
                        "BM25",
                        null));
        variants.put(
                "VECTOR_" + queryMode,
                new VariantAccumulator(
                        "VECTOR_" + queryMode,
                        queryMode,
                        "VECTOR",
                        null));
        for (Integer k : evaluationProperties.getRrfKValues()) {
            String name = "HYBRID_RRF_K" + k + "_" + queryMode;
            variants.put(
                    name,
                    new VariantAccumulator(
                            name,
                            queryMode,
                            "HYBRID_RRF",
                            k));
        }
    }

    private void evaluateCandidateChannels(
            Map<String, VariantAccumulator> variants,
            String queryMode,
            KnowledgeEvaluationCase testCase,
            CandidateChannels channels) {
        variants.get("BM25_" + queryMode)
                .add(candidateResult(testCase, documentIds(channels.lexical())));
        variants.get("VECTOR_" + queryMode)
                .add(candidateResult(testCase, documentIds(channels.vector())));

        for (Integer k : evaluationProperties.getRrfKValues()) {
            List<KnowledgeSearchHit> fused =
                    RrfFusion.fuse(
                            channels.lexical(),
                            channels.vector(),
                            k,
                            backend::normalizeVectorScore);
            variants.get("HYBRID_RRF_K" + k + "_" + queryMode)
                    .add(
                            candidateResult(
                                    testCase,
                                    fused.stream()
                                            .map(KnowledgeSearchHit::documentId)
                                            .distinct()
                                            .toList()));
        }
    }

    private CandidateChannels candidateChannels(String query, int candidateWindow) {
        List<float[]> vectors = embeddingProvider.embed(List.of(query));
        if (vectors.size() != 1) {
            throw new IllegalStateException(
                    "evaluation query embedding must return exactly one vector");
        }
        return new CandidateChannels(
                backend.searchLexical(query, candidateWindow),
                backend.searchVector(vectors.get(0), candidateWindow));
    }

    private String rewriteForEvaluation(String originalQuery) {
        try {
            KnowledgeQueryRewriteResult result = queryRewriter.rewrite(originalQuery);
            if (result == null
                    || result.retrievalQuery() == null
                    || result.retrievalQuery().isBlank()) {
                throw new IllegalStateException("query rewriter returned invalid result");
            }
            return result.retrievalQuery();
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Candidate benchmark requires successful Query Rewrite for controlled A/B",
                    exception);
        }
    }

    private KnowledgeCandidateEvaluationCaseResult candidateResult(
            KnowledgeEvaluationCase testCase,
            List<String> actualDocumentIds) {
        Set<String> expected =
                new LinkedHashSet<>(testCase.expectedDocumentIds());

        Integer rank = firstRelevantRank(actualDocumentIds, expected);
        boolean hitAt1 = rank != null && rank <= 1;
        boolean hitAt3 = rank != null && rank <= 3;
        boolean hitAt5 = rank != null && rank <= 5;
        double reciprocalRank = rank == null ? 0d : 1.0d / rank;

        return new KnowledgeCandidateEvaluationCaseResult(
                testCase.name(),
                testCase.category(),
                testCase.query(),
                testCase.expectedDocumentIds(),
                actualDocumentIds,
                rank,
                reciprocalRank,
                hitAt1,
                hitAt3,
                hitAt5,
                rank != null && rank <= evaluationProperties.getTopK());
    }

    private static List<String> documentIds(List<KnowledgeBackendHit> hits) {
        return hits.stream()
                .map(KnowledgeBackendHit::documentId)
                .distinct()
                .toList();
    }

    private KnowledgeStageDiagnosticsReport evaluateStageDiagnostics(
            KnowledgeCandidateBenchmarkReport candidateBenchmark,
            KnowledgeEvaluationVariantReport baseline,
            KnowledgeEvaluationVariantReport rewrite) {
        Set<String> selected = selectDiagnosticCases(candidateBenchmark, baseline, rewrite);
        int candidateWindow = candidateBenchmark.candidateWindow();
        List<KnowledgeEvaluationStageDiagnostic> diagnostics = new ArrayList<>();

        for (KnowledgeEvaluationCase testCase : dataset.cases()) {
            if (!selected.contains(testCase.name())) {
                continue;
            }
            diagnostics.add(
                    stageDiagnostic(
                            testCase,
                            "ORIGINAL",
                            testCase.query(),
                            candidateWindow));
            diagnostics.add(
                    stageDiagnostic(
                            testCase,
                            "REWRITE",
                            rewriteForEvaluation(testCase.query()),
                            candidateWindow));
        }

        return new KnowledgeStageDiagnosticsReport(
                hybridProperties.getRrfK(),
                candidateWindow,
                List.copyOf(selected),
                diagnostics);
    }

    private Set<String> selectDiagnosticCases(
            KnowledgeCandidateBenchmarkReport candidateBenchmark,
            KnowledgeEvaluationVariantReport baseline,
            KnowledgeEvaluationVariantReport rewrite) {
        Set<String> selected = new LinkedHashSet<>();

        for (KnowledgeEvaluationCaseResult result : baseline.cases()) {
            if (!result.passed()) {
                selected.add(result.name());
            }
        }
        for (KnowledgeEvaluationCaseResult result : rewrite.cases()) {
            if (!result.passed()) {
                selected.add(result.name());
            }
        }

        Map<String, KnowledgeCandidateEvaluationVariantReport> byVariant =
                new LinkedHashMap<>();
        for (KnowledgeCandidateEvaluationVariantReport variant
                : candidateBenchmark.variants()) {
            byVariant.put(variant.variant(), variant);
        }

        addChannelDisagreements(
                selected,
                byVariant.get("BM25_ORIGINAL"),
                byVariant.get("VECTOR_ORIGINAL"));
        addChannelDisagreements(
                selected,
                byVariant.get("BM25_REWRITE"),
                byVariant.get("VECTOR_REWRITE"));

        KnowledgeCandidateEvaluationVariantReport productionOriginal =
                byVariant.get("HYBRID_RRF_K" + hybridProperties.getRrfK() + "_ORIGINAL");
        KnowledgeCandidateEvaluationVariantReport productionRewrite =
                byVariant.get("HYBRID_RRF_K" + hybridProperties.getRrfK() + "_REWRITE");
        addNonRankOne(selected, productionOriginal);
        addNonRankOne(selected, productionRewrite);

        return selected;
    }

    private static void addChannelDisagreements(
            Set<String> selected,
            KnowledgeCandidateEvaluationVariantReport left,
            KnowledgeCandidateEvaluationVariantReport right) {
        if (left == null || right == null) {
            return;
        }
        Map<String, Integer> rightRanks = new LinkedHashMap<>();
        for (KnowledgeCandidateEvaluationCaseResult result : right.cases()) {
            rightRanks.put(result.name(), result.firstRelevantRank());
        }
        for (KnowledgeCandidateEvaluationCaseResult result : left.cases()) {
            Integer other = rightRanks.get(result.name());
            if (!java.util.Objects.equals(result.firstRelevantRank(), other)) {
                selected.add(result.name());
            }
        }
    }

    private static void addNonRankOne(
            Set<String> selected,
            KnowledgeCandidateEvaluationVariantReport variant) {
        if (variant == null) {
            return;
        }
        for (KnowledgeCandidateEvaluationCaseResult result : variant.cases()) {
            if (result.firstRelevantRank() == null || result.firstRelevantRank() > 1) {
                selected.add(result.name());
            }
        }
    }

    private KnowledgeEvaluationStageDiagnostic stageDiagnostic(
            KnowledgeEvaluationCase testCase,
            String queryMode,
            String retrievalQuery,
            int candidateWindow) {
        CandidateChannels channels =
                candidateChannels(retrievalQuery, candidateWindow);
        List<KnowledgeSearchHit> fused =
                RrfFusion.fuse(
                        channels.lexical(),
                        channels.vector(),
                        hybridProperties.getRrfK(),
                        backend::normalizeVectorScore);

        int downstreamLimit =
                Math.max(
                        evaluationProperties.getTopK(),
                        answerabilityProperties.getCandidateLimit());
        int poolLimit =
                Math.max(
                        downstreamLimit,
                        rerankProperties.getCandidateLimit());
        List<KnowledgeSearchHit> rerankInput =
                fused.stream()
                        .limit(Math.min(fused.size(), poolLimit))
                        .toList();

        List<KnowledgeSearchHit> reranked = rerankInput;
        if (!rerankInput.isEmpty()) {
            int rerankOutputLimit =
                    Math.min(rerankInput.size(), downstreamLimit);
            reranked =
                    reranker.rerank(
                                    testCase.query(),
                                    rerankInput,
                                    rerankOutputLimit)
                            .hits();
        }

        List<KnowledgeSearchHit> judgeInput =
                reranked.stream()
                        .limit(answerabilityProperties.getCandidateLimit())
                        .toList();
        List<KnowledgeSearchHit> finalHits = judgeInput;
        String judgeDecision = "NOT_RUN";
        List<String> expectedReasonCodes = List.of();
        boolean judgeSupportedExpected = false;

        if (!judgeInput.isEmpty()) {
            var judged = answerabilityJudge.judge(testCase.query(), judgeInput);
            finalHits =
                    judged.hits().stream()
                            .limit(evaluationProperties.getTopK())
                            .toList();
            if (judged.diagnostics() != null) {
                judgeDecision = judged.diagnostics().decision();
                Set<String> expectedCitations =
                        testCase.expectedDocumentIds().stream()
                                .map(id -> "KB:" + id + "#0")
                                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                List<String> reasons = new ArrayList<>();
                boolean supported = false;
                for (var decision : judged.diagnostics().decisions()) {
                    if (expectedCitations.contains(decision.citationId())) {
                        reasons.add(decision.reasonCode());
                        supported = supported || decision.supported();
                    }
                }
                expectedReasonCodes = List.copyOf(reasons);
                judgeSupportedExpected = supported;
            }
        }

        Set<String> expected =
                new LinkedHashSet<>(testCase.expectedDocumentIds());
        List<String> lexicalIds = documentIds(channels.lexical());
        List<String> vectorIds = documentIds(channels.vector());
        List<String> fusedIds =
                fused.stream()
                        .map(KnowledgeSearchHit::documentId)
                        .distinct()
                        .toList();
        List<String> rerankIds =
                reranked.stream()
                        .map(KnowledgeSearchHit::documentId)
                        .distinct()
                        .toList();
        List<String> finalIds =
                finalHits.stream()
                        .map(KnowledgeSearchHit::documentId)
                        .distinct()
                        .toList();

        return new KnowledgeEvaluationStageDiagnostic(
                testCase.name(),
                testCase.category(),
                queryMode,
                testCase.query(),
                retrievalQuery,
                testCase.expectedDocumentIds(),
                firstRelevantRank(lexicalIds, expected),
                firstRelevantRank(vectorIds, expected),
                firstRelevantRank(fusedIds, expected),
                firstRelevantRank(rerankIds, expected),
                firstRelevantRank(finalIds, expected),
                top(lexicalIds, 3),
                top(vectorIds, 3),
                top(fusedIds, 5),
                top(rerankIds, 5),
                finalIds,
                judgeDecision,
                expectedReasonCodes,
                judgeSupportedExpected,
                finalIds.isEmpty());
    }

    private static List<String> top(List<String> values, int limit) {
        return values.stream().limit(limit).toList();
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
                        .distinct()
                        .toList();
        Set<String> expected =
                new LinkedHashSet<>(testCase.expectedDocumentIds());

        Integer rank = firstRelevantRank(actualDocumentIds, expected);
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

    private static Integer firstRelevantRank(
            List<String> actualDocumentIds,
            Set<String> expected) {
        for (int i = 0; i < actualDocumentIds.size(); i++) {
            if (expected.contains(actualDocumentIds.get(i))) {
                return i + 1;
            }
        }
        return null;
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
        if (dataset.documents().size() < 10) {
            throw new IllegalStateException(
                    "Evaluation dataset must contain at least 10 documents.");
        }
        if (dataset.cases().size() < 20) {
            throw new IllegalStateException(
                    "Evaluation dataset must contain at least 20 cases.");
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

        if (answerable < 10 || noAnswer < 3) {
            throw new IllegalStateException(
                    "Evaluation dataset needs at least 10 answerable and 3 no-answer cases.");
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

    private record CandidateChannels(
            List<KnowledgeBackendHit> lexical,
            List<KnowledgeBackendHit> vector) {}

    private static final class VariantAccumulator {

        private final String variant;
        private final String queryMode;
        private final String retrievalStrategy;
        private final Integer rrfK;
        private final List<KnowledgeCandidateEvaluationCaseResult> cases =
                new ArrayList<>();

        private VariantAccumulator(
                String variant,
                String queryMode,
                String retrievalStrategy,
                Integer rrfK) {
            this.variant = variant;
            this.queryMode = queryMode;
            this.retrievalStrategy = retrievalStrategy;
            this.rrfK = rrfK;
        }

        private void add(KnowledgeCandidateEvaluationCaseResult result) {
            cases.add(result);
        }

        private KnowledgeCandidateEvaluationVariantReport report() {
            int hitAt1 = 0;
            int hitAt3 = 0;
            int hitAt5 = 0;
            double reciprocalRank = 0d;
            for (KnowledgeCandidateEvaluationCaseResult result : cases) {
                if (result.hitAt1()) {
                    hitAt1++;
                }
                if (result.hitAt3()) {
                    hitAt3++;
                }
                if (result.hitAt5()) {
                    hitAt5++;
                }
                reciprocalRank += result.reciprocalRank();
            }
            return new KnowledgeCandidateEvaluationVariantReport(
                    variant,
                    queryMode,
                    retrievalStrategy,
                    rrfK,
                    cases.size(),
                    ratio(hitAt1, cases.size()),
                    ratio(hitAt3, cases.size()),
                    ratio(hitAt5, cases.size()),
                    cases.isEmpty() ? 0d : reciprocalRank / cases.size(),
                    cases);
        }
    }
}
