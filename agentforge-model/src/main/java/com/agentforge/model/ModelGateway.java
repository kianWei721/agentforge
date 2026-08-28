package com.agentforge.model;

import reactor.core.publisher.Flux;

/** Unified model gateway. Business modules depend on this interface instead of a specific LLM SDK. */
public interface ModelGateway {
    Flux<ModelChunk> stream(ModelRequest request);
}
