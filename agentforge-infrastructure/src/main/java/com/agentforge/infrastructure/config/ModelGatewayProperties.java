package com.agentforge.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * Connection to the standalone model gateway. When enabled, every chat-completion call AgentForge
 * makes for knowledge search goes through it; there is no silent fallback to calling DashScope
 * directly.
 */
@ConfigurationProperties(prefix = "agentforge.model-gateway")
public class ModelGatewayProperties {

    private boolean enabled;
    private String baseUrl;
    private String apiKey;
    private String ragRoute = "rag-json";
    private String chatRoute = "standard";
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration requestTimeout = Duration.ofSeconds(30);
    /** Agent model calls: the gateway reserves budget by max tokens, so it is always sent. */
    private int agentMaxTokens = 2048;
    /** One agent model call; the gateway itself stops at 45 s. */
    private Duration agentRequestTimeout = Duration.ofSeconds(40);

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

    public String getRagRoute() {
        return ragRoute;
    }

    public void setRagRoute(String ragRoute) {
        this.ragRoute = ragRoute;
    }

    public String getChatRoute() {
        return chatRoute;
    }

    public void setChatRoute(String chatRoute) {
        this.chatRoute = chatRoute;
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

    public int getAgentMaxTokens() {
        return agentMaxTokens;
    }

    public void setAgentMaxTokens(int agentMaxTokens) {
        this.agentMaxTokens = agentMaxTokens;
    }

    public Duration getAgentRequestTimeout() {
        return agentRequestTimeout;
    }

    public void setAgentRequestTimeout(Duration agentRequestTimeout) {
        this.agentRequestTimeout = agentRequestTimeout;
    }

    public void validate() {
        if (!enabled) {
            return;
        }
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException("Model gateway requires base URL.");
        }
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("Model gateway requires API key.");
        }
        if (!StringUtils.hasText(ragRoute) || !StringUtils.hasText(chatRoute)) {
            throw new IllegalStateException("Model gateway requires ragRoute and chatRoute.");
        }
        if (!positive(connectTimeout) || !positive(requestTimeout) || !positive(agentRequestTimeout)) {
            throw new IllegalStateException("Model gateway timeouts must be positive.");
        }
        if (agentMaxTokens < 1) {
            throw new IllegalStateException("Model gateway agent-max-tokens must be positive.");
        }
    }

    private static boolean positive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
