package com.agentforge.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentforge.model")
public class ModelProviderProperties {
    private String provider = "mock";
    private final Mock mock = new Mock();
    private final DashScope dashscope = new DashScope();
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public Mock getMock() { return mock; }
    public DashScope getDashscope() { return dashscope; }
    public static class Mock {
        private String model = "mock-stream-v0";
        private long delayMillis = 120;
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public long getDelayMillis() { return delayMillis; }
        public void setDelayMillis(long delayMillis) { this.delayMillis = delayMillis; }
    }
    public static class DashScope {
        private String apiKey;
        private String baseUrl;
        private String model = "qwen-plus";
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }
}
