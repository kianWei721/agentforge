package com.agentforge.infrastructure.rag;

import com.agentforge.infrastructure.config.KnowledgeAnswerabilityProperties;
import com.agentforge.infrastructure.config.KnowledgeEvaluationProperties;
import com.agentforge.infrastructure.config.KnowledgeHybridProperties;
import com.agentforge.infrastructure.config.KnowledgeQueryRewriteProperties;
import com.agentforge.infrastructure.config.KnowledgeRerankProperties;
import com.agentforge.infrastructure.config.ModelGatewayProperties;
import com.agentforge.infrastructure.gateway.ModelGatewayClient;
import com.agentforge.rag.EmbeddingProvider;
import com.agentforge.rag.HybridKnowledgeRetriever;
import com.agentforge.rag.KnowledgeAccessGuard;
import com.agentforge.rag.KnowledgeAnswerabilityJudge;
import com.agentforge.rag.KnowledgeChunker;
import com.agentforge.rag.KnowledgeEvaluator;
import com.agentforge.rag.KnowledgeIndexService;
import com.agentforge.rag.KnowledgeIngestionService;
import com.agentforge.rag.KnowledgeQueryRewriter;
import com.agentforge.rag.KnowledgeRepository;
import com.agentforge.rag.KnowledgeReranker;
import com.agentforge.rag.KnowledgeRetriever;
import com.agentforge.rag.KnowledgeSearchBackend;
import com.agentforge.rag.LexicalKnowledgeRetriever;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class KnowledgeRuntimeConfiguration {

    @Bean
    public KnowledgeRepository knowledgeRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcKnowledgeRepository(jdbcTemplate);
    }

    @Bean
    public KnowledgeChunker knowledgeChunker() {
        return new KnowledgeChunker(800, 120);
    }

    @Bean
    public KnowledgeIngestionService knowledgeIngestionService(
            KnowledgeRepository repository, KnowledgeChunker chunker) {
        return new KnowledgeIngestionService(repository, chunker);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "agentforge.knowledge.hybrid",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true)
    public KnowledgeRetriever lexicalKnowledgeRetriever(KnowledgeRepository repository) {
        return new LexicalKnowledgeRetriever(repository);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "agentforge.knowledge.hybrid",
            name = "enabled",
            havingValue = "true")
    public EmbeddingProvider knowledgeEmbeddingProvider(KnowledgeHybridProperties properties) {
        properties.validate();
        HttpClient client =
                HttpClient.newBuilder()
                        .connectTimeout(properties.getEmbedding().getRequestTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        return new DashScopeEmbeddingProvider(
                client, new ObjectMapper(), properties.getEmbedding());
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "agentforge.knowledge.hybrid",
            name = "enabled",
            havingValue = "true")
    public KnowledgeSearchBackend knowledgeSearchBackend(KnowledgeHybridProperties properties) {
        properties.validate();
        HttpClient client =
                HttpClient.newBuilder()
                        .connectTimeout(properties.getElasticsearch().getConnectTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        return new ElasticsearchKnowledgeSearchBackend(
                client, new ObjectMapper(), properties.getElasticsearch());
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "agentforge.knowledge.query-rewrite",
            name = "enabled",
            havingValue = "true")
    public KnowledgeQueryRewriter knowledgeQueryRewriter(
            KnowledgeQueryRewriteProperties properties,
            ModelGatewayProperties gatewayProperties,
            ObjectProvider<ModelGatewayClient> gatewayClientProvider) {
        // With the gateway on, no call falls back to DashScope directly.
        ModelGatewayClient gateway =
                gatewayProperties.isEnabled() ? gatewayClientProvider.getObject() : null;
        KnowledgeQueryRewriteProperties effective =
                gateway == null ? properties : properties.viaGateway(gatewayProperties);
        effective.validate();
        HttpClient client =
                HttpClient.newBuilder()
                        .connectTimeout(effective.getRequestTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        return new DashScopeKnowledgeQueryRewriter(
                client, new ObjectMapper(), effective, gateway);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "agentforge.knowledge.rerank",
            name = "enabled",
            havingValue = "true")
    public KnowledgeReranker knowledgeReranker(KnowledgeRerankProperties properties) {
        properties.validate();
        HttpClient client =
                HttpClient.newBuilder()
                        .connectTimeout(properties.getRequestTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        return new DashScopeKnowledgeReranker(
                client, new ObjectMapper(), properties);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "agentforge.knowledge.answerability",
            name = "enabled",
            havingValue = "true")
    public KnowledgeAnswerabilityJudge knowledgeAnswerabilityJudge(
            KnowledgeAnswerabilityProperties properties,
            ModelGatewayProperties gatewayProperties,
            ObjectProvider<ModelGatewayClient> gatewayClientProvider) {
        // With the gateway on, no call falls back to DashScope directly.
        ModelGatewayClient gateway =
                gatewayProperties.isEnabled() ? gatewayClientProvider.getObject() : null;
        KnowledgeAnswerabilityProperties effective =
                gateway == null ? properties : properties.viaGateway(gatewayProperties);
        effective.validate();
        HttpClient client =
                HttpClient.newBuilder()
                        .connectTimeout(effective.getRequestTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        return new DashScopeKnowledgeAnswerabilityJudge(
                client, new ObjectMapper(), effective, gateway);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "agentforge.knowledge.hybrid",
            name = "enabled",
            havingValue = "true")
    public KnowledgeRetriever hybridKnowledgeRetriever(
            EmbeddingProvider embeddingProvider,
            KnowledgeSearchBackend backend,
            KnowledgeRepository repository,
            KnowledgeHybridProperties properties,
            KnowledgeQueryRewriteProperties queryRewriteProperties,
            KnowledgeRerankProperties rerankProperties,
            KnowledgeAnswerabilityProperties answerabilityProperties,
            ObjectProvider<KnowledgeQueryRewriter> queryRewriterProvider,
            ObjectProvider<KnowledgeReranker> rerankerProvider,
            ObjectProvider<KnowledgeAnswerabilityJudge> answerabilityJudgeProvider) {
        return new HybridKnowledgeRetriever(
                embeddingProvider,
                backend,
                new KnowledgeAccessGuard(repository),
                properties.getCandidateMultiplier(),
                properties.getRrfK(),
                properties.isRelevanceGateEnabled(),
                properties.getMinVectorSimilarity(),
                queryRewriterProvider.getIfAvailable(),
                rerankerProvider.getIfAvailable(),
                rerankProperties.getCandidateLimit(),
                answerabilityJudgeProvider.getIfAvailable(),
                answerabilityProperties.getCandidateLimit());
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "agentforge.knowledge.evaluation",
            name = "enabled",
            havingValue = "true")
    public KnowledgeEvaluator knowledgeEvaluator(
            EmbeddingProvider embeddingProvider,
            KnowledgeHybridProperties hybridProperties,
            KnowledgeRerankProperties rerankProperties,
            KnowledgeAnswerabilityProperties answerabilityProperties,
            KnowledgeEvaluationProperties evaluationProperties,
            ObjectProvider<KnowledgeQueryRewriter> queryRewriterProvider,
            ObjectProvider<KnowledgeReranker> rerankerProvider,
            ObjectProvider<KnowledgeAnswerabilityJudge> answerabilityJudgeProvider,
            ResourceLoader resourceLoader) {
        KnowledgeQueryRewriter rewriter =
                queryRewriterProvider.getIfAvailable(
                        () -> {
                            throw new IllegalStateException(
                                    "Knowledge evaluation requires Query Rewrite.");
                        });
        KnowledgeReranker reranker =
                rerankerProvider.getIfAvailable(
                        () -> {
                            throw new IllegalStateException(
                                    "Knowledge evaluation requires Reranker.");
                        });
        KnowledgeAnswerabilityJudge judge =
                answerabilityJudgeProvider.getIfAvailable(
                        () -> {
                            throw new IllegalStateException(
                                    "Knowledge evaluation requires Answerability Judge.");
                        });
        return new ElasticsearchGoldDatasetKnowledgeEvaluator(
                embeddingProvider,
                hybridProperties,
                rerankProperties,
                answerabilityProperties,
                evaluationProperties,
                rewriter,
                reranker,
                judge,
                resourceLoader,
                new ObjectMapper());
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "agentforge.knowledge.hybrid",
            name = "enabled",
            havingValue = "true")
    public KnowledgeIndexService knowledgeIndexService(
            KnowledgeRepository repository,
            EmbeddingProvider embeddingProvider,
            KnowledgeSearchBackend backend,
            KnowledgeHybridProperties properties) {
        return new KnowledgeIndexService(
                repository,
                embeddingProvider,
                backend,
                properties.getEmbedding().getBatchSize(),
                properties.getMaxCandidates());
    }
}
