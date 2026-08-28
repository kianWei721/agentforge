package com.agentforge.infrastructure.model;

import com.agentforge.infrastructure.config.ModelProviderProperties;
import com.agentforge.model.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import java.util.List;

class RoutingModelGatewayTest {
    @Test void shouldRouteToConfiguredProvider() {
        ModelProviderProperties properties = new ModelProviderProperties(); properties.setProvider("test");
        ModelProvider testProvider = new ModelProvider() {
            public String provider(){return "test";} public String model(){return "test-model";}
            public Flux<ModelChunk> stream(ModelRequest request){return Flux.just(new ModelChunk("ok",false,provider(),model()),new ModelChunk("",true,provider(),model()));}
        };
        RoutingModelGateway gateway = new RoutingModelGateway(properties, List.of(testProvider));
        StepVerifier.create(gateway.stream(new ModelRequest("u1","s1","hello")))
                .expectNextMatches(chunk -> "ok".equals(chunk.content()) && "test".equals(chunk.provider()))
                .expectNextMatches(ModelChunk::done).verifyComplete();
    }
    @Test void shouldFailWhenProviderIsUnknown() {
        ModelProviderProperties properties = new ModelProviderProperties(); properties.setProvider("missing");
        RoutingModelGateway gateway = new RoutingModelGateway(properties, List.of());
        StepVerifier.create(gateway.stream(new ModelRequest("u1","s1","hello")))
                .expectErrorMatches(error -> error instanceof IllegalStateException && error.getMessage().contains("missing")).verify();
    }
}
