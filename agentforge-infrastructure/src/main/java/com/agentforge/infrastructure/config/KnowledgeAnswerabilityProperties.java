package com.agentforge.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "agentforge.knowledge.answerability")
public class KnowledgeAnswerabilityProperties {

    private boolean enabled;
    private String baseUrl;
    private String apiKey;
    private String model = "qwen3.8-flash";
    private int candidateLimit = 8;
    private Duration requestTimeout = Duration.ofSeconds(20);

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

    /**
     * This configuration redirected to the model gateway: the gateway's URL and key, and its RAG
     * route as the model. Nothing of the direct DashScope settings is used.
     */
    public KnowledgeAnswerabilityProperties viaGateway(ModelGatewayProperties gateway) {
        KnowledgeAnswerabilityProperties copy = new KnowledgeAnswerabilityProperties();
        copy.setEnabled(enabled);
        copy.setBaseUrl(gateway.getBaseUrl());
        copy.setApiKey(gateway.getApiKey());
        copy.setModel(gateway.getRagRoute());
        copy.setCandidateLimit(candidateLimit);
        copy.setRequestTimeout(requestTimeout);
        return copy;
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
            throw new IllegalStateException(
                    "Knowledge answerability requires base URL.");
        }
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException(
                    "Knowledge answerability requires API key.");
        }
        if (!StringUtils.hasText(model)) {
            throw new IllegalStateException(
                    "Knowledge answerability requires model.");
        }
        if (candidateLimit < 1 || candidateLimit > 20) {
            throw new IllegalStateException(
                    "Answerability candidateLimit must be between 1 and 20.");
        }
        if (requestTimeout == null
                || requestTimeout.isZero()
                || requestTimeout.isNegative()) {
            throw new IllegalStateException(
                    "Answerability requestTimeout must be positive.");
        }
    }
}
