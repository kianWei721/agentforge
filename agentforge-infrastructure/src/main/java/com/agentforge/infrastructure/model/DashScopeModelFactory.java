package com.agentforge.infrastructure.model;

import com.agentforge.infrastructure.config.ModelProviderProperties;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class DashScopeModelFactory {

    private final ModelProviderProperties properties;

    public DashScopeModelFactory(ModelProviderProperties properties) {
        this.properties = properties;
    }

    public DashScopeChatModel createStreamingModel() {
        return createStreamingModel(properties.getDashscope().getModel());
    }

    public DashScopeChatModel createStreamingModel(String requestedModelName) {
        ModelProviderProperties.DashScope config = properties.getDashscope();
        requireApiKey(config.getApiKey());
        String modelName =
                StringUtils.hasText(requestedModelName)
                        ? requestedModelName.trim()
                        : config.getModel();

        if (StringUtils.hasText(config.getBaseUrl())) {
            return DashScopeChatModel.builder()
                    .apiKey(config.getApiKey())
                    .baseUrl(config.getBaseUrl())
                    .modelName(modelName)
                    .stream(true)
                    .formatter(new DashScopeChatFormatter())
                    .build();
        }
        return DashScopeChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(modelName)
                .stream(true)
                .formatter(new DashScopeChatFormatter())
                .build();
    }

    private static void requireApiKey(String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException(
                    "DashScope API key is missing. Set environment variable DASHSCOPE_API_KEY before running an Agent.");
        }
    }
}
