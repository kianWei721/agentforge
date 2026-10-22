package com.agentforge.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "agentforge.knowledge.query-rewrite")
public class KnowledgeQueryRewriteProperties {

    private boolean enabled;
    private String baseUrl;
    private String apiKey;
    private String model = "qwen3.8-flash";
    private Duration requestTimeout = Duration.ofSeconds(15);
    private int maxQueryChars = 512;

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

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public int getMaxQueryChars() {
        return maxQueryChars;
    }

    public void setMaxQueryChars(int maxQueryChars) {
        this.maxQueryChars = maxQueryChars;
    }

    /**
     * This configuration redirected to the model gateway: the gateway's URL and key, and its RAG
     * route as the model. Nothing of the direct DashScope settings is used.
     */
    public KnowledgeQueryRewriteProperties viaGateway(ModelGatewayProperties gateway) {
        KnowledgeQueryRewriteProperties copy = new KnowledgeQueryRewriteProperties();
        copy.setEnabled(enabled);
        copy.setBaseUrl(gateway.getBaseUrl());
        copy.setApiKey(gateway.getApiKey());
        copy.setModel(gateway.getRagRoute());
        copy.setRequestTimeout(requestTimeout);
        copy.setMaxQueryChars(maxQueryChars);
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
            throw new IllegalStateException("Knowledge query rewrite requires base URL.");
        }
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("Knowledge query rewrite requires API key.");
        }
        if (!StringUtils.hasText(model)) {
            throw new IllegalStateException("Knowledge query rewrite requires model.");
        }
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalStateException("Knowledge query rewrite requestTimeout must be positive.");
        }
        if (maxQueryChars < 64 || maxQueryChars > 2048) {
            throw new IllegalStateException(
                    "Knowledge query rewrite maxQueryChars must be between 64 and 2048.");
        }
    }
}
