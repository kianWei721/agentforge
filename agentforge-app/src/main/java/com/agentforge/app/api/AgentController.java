package com.agentforge.app.api;

import com.agentforge.core.AgentForgeConstants;
import com.agentforge.runtime.AgentRuntimeManager;
import com.agentforge.runtime.AgentRuntimeRequest;
import com.agentforge.runtime.AgentStreamChunk;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@ConditionalOnProperty(name = "agentforge.agent.enabled", havingValue = "true")
@RequestMapping(AgentForgeConstants.API_PREFIX + "/agents")
public class AgentController {

    private final AgentRuntimeManager runtimeManager;

    public AgentController(AgentRuntimeManager runtimeManager) {
        this.runtimeManager = runtimeManager;
    }

    @PostMapping(value = "/{agentId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AgentStreamChunk>> stream(
            @PathVariable String agentId,
            @Valid @RequestBody ChatRequest request) {
        AgentRuntimeRequest runtimeRequest =
                new AgentRuntimeRequest(request.userId(), request.sessionId(), request.message());
        return runtimeManager
                .stream(agentId, runtimeRequest)
                .map(
                        chunk ->
                                ServerSentEvent.<AgentStreamChunk>builder()
                                        .event(chunk.type())
                                        .data(chunk)
                                        .build());
    }
}
