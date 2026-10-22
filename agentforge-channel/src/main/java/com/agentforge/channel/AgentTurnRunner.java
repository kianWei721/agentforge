package com.agentforge.channel;

import com.agentforge.runtime.AgentRuntimeRequest;
import com.agentforge.runtime.AgentStreamChunk;
import reactor.core.publisher.Flux;

/** Runs one conversational agent turn; in production {@code AgentConversationRuntimeService}. */
@FunctionalInterface
public interface AgentTurnRunner {

    Flux<AgentStreamChunk> stream(String agentId, AgentRuntimeRequest request);
}
