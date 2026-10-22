package com.agentforge.infrastructure.gateway;

import com.agentforge.infrastructure.config.ModelGatewayProperties;
import com.agentforge.infrastructure.config.ModelProviderProperties;
import com.agentforge.infrastructure.model.GatewayModelProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ModelGatewayConfiguration {

    /**
     * Choosing the gateway for chat while the gateway is switched off would fail every chat at
     * request time; fail at startup instead.
     */
    public ModelGatewayConfiguration(
            ModelGatewayProperties gatewayProperties, ModelProviderProperties modelProperties) {
        String provider = modelProperties.getProvider();
        if (provider != null
                && GatewayModelProvider.PROVIDER.equalsIgnoreCase(provider.trim())
                && !gatewayProperties.isEnabled()) {
            throw new IllegalStateException(
                    "agentforge.model.provider=gateway requires agentforge.model-gateway.enabled=true.");
        }
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "agentforge.model-gateway",
            name = "enabled",
            havingValue = "true")
    public ModelGatewayClient modelGatewayClient(ModelGatewayProperties properties) {
        properties.validate();
        HttpClient client =
                HttpClient.newBuilder()
                        .connectTimeout(properties.getConnectTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        return new ModelGatewayClient(client, new ObjectMapper(), properties);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "agentforge.model-gateway",
            name = "enabled",
            havingValue = "true")
    public GatewayModelProvider gatewayModelProvider(
            ModelGatewayClient client, ModelGatewayProperties properties) {
        return new GatewayModelProvider(client, properties);
    }
}
