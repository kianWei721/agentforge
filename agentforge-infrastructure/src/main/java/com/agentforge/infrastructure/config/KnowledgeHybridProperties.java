package com.agentforge.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "agentforge.knowledge.hybrid")
public class KnowledgeHybridProperties {

    private boolean enabled;
    private int candidateMultiplier = 4;
    private int rrfK = 60;
    private int maxCandidates = 100000;
    private final Embedding embedding = new Embedding();
    private final Elasticsearch elasticsearch = new Elasticsearch();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getCandidateMultiplier() {
        return candidateMultiplier;
    }

    public void setCandidateMultiplier(int candidateMultiplier) {
        this.candidateMultiplier = candidateMultiplier;
    }

    public int getRrfK() {
        return rrfK;
    }

    public void setRrfK(int rrfK) {
        this.rrfK = rrfK;
    }

    public int getMaxCandidates() {
        return maxCandidates;
    }

    public void setMaxCandidates(int maxCandidates) {
        this.maxCandidates = maxCandidates;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public Elasticsearch getElasticsearch() {
        return elasticsearch;
    }

    public boolean isConfigured() {
        return enabled && embedding.isConfigured() && elasticsearch.isConfigured();
    }

    public void validate() {
        if (!enabled) {
            return;
        }
        if (!embedding.isConfigured()) {
            throw new IllegalStateException(
                    "Knowledge hybrid retrieval requires embedding base URL and API key.");
        }
        if (!elasticsearch.isConfigured()) {
            throw new IllegalStateException(
                    "Knowledge hybrid retrieval requires Elasticsearch base URL and index name.");
        }
        if (embedding.dimensions < 64 || embedding.dimensions > 2048) {
            throw new IllegalStateException("Embedding dimensions must be between 64 and 2048.");
        }
        if (embedding.batchSize < 1 || embedding.batchSize > 10) {
            throw new IllegalStateException("Embedding batch size must be between 1 and 10.");
        }
        if (candidateMultiplier < 1 || candidateMultiplier > 20) {
            throw new IllegalStateException("candidateMultiplier must be between 1 and 20.");
        }
        if (rrfK < 1) {
            throw new IllegalStateException("rrfK must be positive.");
        }
        if (maxCandidates < 1) {
            throw new IllegalStateException("maxCandidates must be positive.");
        }
    }

    public static class Embedding {
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        private String apiKey;
        private String model = "text-embedding-v4";
        private int dimensions = 1024;
        private int batchSize = 10;
        private Duration requestTimeout = Duration.ofSeconds(15);

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getDimensions() {
            return dimensions;
        }

        public void setDimensions(int dimensions) {
            this.dimensions = dimensions;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }

        private boolean isConfigured() {
            return StringUtils.hasText(baseUrl)
                    && StringUtils.hasText(apiKey)
                    && StringUtils.hasText(model);
        }
    }

    public static class Elasticsearch {
        private String baseUrl;
        private String indexName = "agentforge-knowledge-v1";
        private String username;
        private String password;
        private String apiKey;
        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration requestTimeout = Duration.ofSeconds(5);

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getIndexName() {
            return indexName;
        }

        public void setIndexName(String indexName) {
            this.indexName = indexName;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }

        public boolean isConfigured() {
            return StringUtils.hasText(baseUrl) && StringUtils.hasText(indexName);
        }
    }
}
