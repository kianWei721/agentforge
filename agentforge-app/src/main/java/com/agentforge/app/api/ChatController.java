package com.agentforge.app.api;

import com.agentforge.core.AgentForgeConstants;
import com.agentforge.core.security.AccessPrincipal;
import com.agentforge.model.*;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping(AgentForgeConstants.API_PREFIX + "/chat")
public class ChatController {
    private final ModelGateway modelGateway;
    public ChatController(ModelGateway modelGateway) { this.modelGateway = modelGateway; }
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ModelChunk>> stream(@Valid @RequestBody ChatRequest request, AccessPrincipal principal) {
        ModelRequest modelRequest = new ModelRequest(principal.userId(), request.sessionId(), request.message());
        return modelGateway.stream(modelRequest).map(chunk -> ServerSentEvent.<ModelChunk>builder().event(chunk.done() ? "done" : "message").data(chunk).build());
    }
}
