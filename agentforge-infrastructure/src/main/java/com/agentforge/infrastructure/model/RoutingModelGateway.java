package com.agentforge.infrastructure.model;

import com.agentforge.infrastructure.config.ModelProviderProperties;
import com.agentforge.model.*;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class RoutingModelGateway implements ModelGateway, ModelGatewayCatalog {
    private final ModelProviderProperties properties;
    private final Map<String, ModelProvider> providers;
    public RoutingModelGateway(ModelProviderProperties properties, List<ModelProvider> providers) {
        this.properties = properties;
        this.providers = providers.stream().collect(Collectors.toMap(p -> normalize(p.provider()), p -> p, (l, r) -> { throw new IllegalStateException("Duplicate model provider: " + l.provider()); }, LinkedHashMap::new));
    }
    @Override public Flux<ModelChunk> stream(ModelRequest request) {
        String providerName = normalize(properties.getProvider());
        ModelProvider provider = providers.get(providerName);
        if (provider == null) return Flux.error(new IllegalStateException("Model provider '" + providerName + "' is not registered. Available providers: " + providers.keySet()));
        return provider.stream(request);
    }
    @Override public String activeProvider() { return normalize(properties.getProvider()); }
    @Override public Map<String, String> registeredProviders() { return providers.values().stream().collect(Collectors.toUnmodifiableMap(ModelProvider::provider, ModelProvider::model)); }
    private static String normalize(String provider) { return !StringUtils.hasText(provider) ? "mock" : provider.trim().toLowerCase(Locale.ROOT); }
}
