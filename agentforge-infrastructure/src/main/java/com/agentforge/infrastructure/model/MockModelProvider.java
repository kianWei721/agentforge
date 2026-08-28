package com.agentforge.infrastructure.model;

import com.agentforge.infrastructure.config.ModelProviderProperties;
import com.agentforge.model.ModelChunk;
import com.agentforge.model.ModelProvider;
import com.agentforge.model.ModelRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.Arrays;

@Component
public class MockModelProvider implements ModelProvider {
    private static final String PROVIDER = "mock";
    private final ModelProviderProperties properties;
    public MockModelProvider(ModelProviderProperties properties) { this.properties = properties; }
    @Override public String provider() { return PROVIDER; }
    @Override public String model() { return properties.getMock().getModel(); }
    @Override public Flux<ModelChunk> stream(ModelRequest request) {
        String answer = "AgentForge 模型网关路由已打通。当前使用 Mock Provider；你输入的是：" + request.message();
        long delayMillis = Math.max(0, properties.getMock().getDelayMillis());
        Flux<ModelChunk> content = Flux.fromIterable(Arrays.asList(answer.split("(?<=。)|(?<=；)|(?<=：)|(?<=，)")))
                .filter(part -> !part.isBlank()).delayElements(Duration.ofMillis(delayMillis))
                .map(part -> new ModelChunk(part, false, provider(), model()));
        return content.concatWithValues(new ModelChunk("", true, provider(), model()));
    }
}
