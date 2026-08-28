package com.agentforge.infrastructure.model;

import com.agentforge.infrastructure.config.ModelProviderProperties;
import com.agentforge.model.ModelRequest;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import java.time.Duration;

class MockModelProviderTest {
    @Test void shouldStreamContentAndFinish() {
        ModelProviderProperties properties = new ModelProviderProperties();
        properties.getMock().setDelayMillis(10);
        MockModelProvider provider = new MockModelProvider(properties);
        StepVerifier.withVirtualTime(() -> provider.stream(new ModelRequest("u1", "s1", "hello")))
                .thenAwait(Duration.ofSeconds(2)).thenConsumeWhile(chunk -> !chunk.done())
                .expectNextMatches(chunk -> chunk.done() && chunk.content().isEmpty()).verifyComplete();
    }
}
