package com.agentforge.app.api;

import com.agentforge.core.AgentForgeConstants;
import com.agentforge.runtime.AgentConversationRuntimeService;
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
@RequestMapping(AgentForgeConstants.API_PREFIX + "/agent")
public class LegacyAgentController {

    private final AgentConversationRuntimeService conversationRuntimeService;

    public LegacyAgentController(AgentConversationRuntimeService conversationRuntimeService) {
        this.conversationRuntimeService = conversationRuntimeService;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AgentStreamChunk>> stream(@Valid @RequestBody ChatRequest request) {
        AgentRuntimeRequest runtimeRequest =
                new AgentRuntimeRequest(request.userId(), request.sessionId(), request.message());
        return conversationRuntimeService
                .stream("default", runtimeRequest)
                .map(
                        chunk ->
                                ServerSentEvent.<AgentStreamChunk>builder()
                                        .event(chunk.type())
                                        .data(chunk)
                                        .build());
    }
}
