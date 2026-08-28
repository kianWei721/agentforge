package com.agentforge.runtime;

import reactor.core.publisher.Flux;

public interface AgentRuntime {
    Flux<AgentStreamChunk> stream(AgentRuntimeRequest request);
}
