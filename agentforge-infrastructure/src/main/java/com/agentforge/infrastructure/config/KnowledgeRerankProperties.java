package com.agentforge.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "agentforge.knowledge.rerank")
public class KnowledgeRerankProperties {

    private boolean enabled;
    private String baseUrl;
    private String apiKey;
    private String model = "qwen3-rerank";
    private int candidateLimit = 8;
    private Duration requestTimeout = Duration.ofSeconds(15);
    private String instruct =
            "Rank passages by whether they directly answer the user's specific question. "
                    + "If the real knowledge passages do not contain enough information to answer "
                    + "the question, prefer the NO_ANSWER_SENTINEL passage over merely topically "
                    + "related passages.";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

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

    public int getCandidateLimit() {
        return candidateLimit;
    }

    public void setCandidateLimit(int candidateLimit) {
        this.candidateLimit = candidateLimit;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public String getInstruct() {
        return instruct;
    }

    public void setInstruct(String instruct) {
        this.instruct = instruct;
    }

    public boolean isConfigured() {
        return enabled
                && StringUtils.hasText(baseUrl)
                && StringUtils.hasText(apiKey)
                && StringUtils.hasText(model);
    }

    public void validate() {
        if (!enabled) {
            return;
        }
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException("Knowledge rerank requires base URL.");
        }
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("Knowledge rerank requires API key.");
        }
        if (!StringUtils.hasText(model)) {
            throw new IllegalStateException("Knowledge rerank requires model.");
        }
        if (candidateLimit < 1 || candidateLimit > 100) {
            throw new IllegalStateException("Rerank candidateLimit must be between 1 and 100.");
        }
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalStateException("Rerank requestTimeout must be positive.");
        }
        if (!StringUtils.hasText(instruct)) {
            throw new IllegalStateException("Rerank instruct must not be blank.");
        }
    }
}
