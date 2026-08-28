package com.agentforge.model;

import reactor.core.publisher.Flux;

public interface ModelProvider {
    String provider();
    String model();
    Flux<ModelChunk> stream(ModelRequest request);
}
