package com.agentforge.infrastructure.rag;

import com.agentforge.infrastructure.config.KnowledgeHybridProperties;
import com.agentforge.infrastructure.config.KnowledgeRerankProperties;
import com.agentforge.rag.EmbeddingProvider;
import com.agentforge.rag.HybridKnowledgeRetriever;
import com.agentforge.rag.KnowledgeChunker;
import com.agentforge.rag.KnowledgeIndexService;
import com.agentforge.rag.KnowledgeIngestionService;
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
            prefix = "agentforge.knowledge.hybrid",
            name = "enabled",
            havingValue = "true")
    public KnowledgeRetriever hybridKnowledgeRetriever(
            EmbeddingProvider embeddingProvider,
            KnowledgeSearchBackend backend,
            KnowledgeHybridProperties properties,
            KnowledgeRerankProperties rerankProperties,
            ObjectProvider<KnowledgeReranker> rerankerProvider) {
        return new HybridKnowledgeRetriever(
                embeddingProvider,
                backend,
                properties.getCandidateMultiplier(),
                properties.getRrfK(),
                properties.isRelevanceGateEnabled(),
                properties.getMinVectorSimilarity(),
                rerankerProvider.getIfAvailable(),
                rerankProperties.getCandidateLimit());
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
