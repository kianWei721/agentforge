package com.agentforge.infrastructure.model;

import com.agentforge.infrastructure.config.ModelGatewayProperties;
import com.agentforge.infrastructure.gateway.ModelGatewayClient;
import com.agentforge.model.ModelChunk;
import com.agentforge.model.ModelProvider;
import com.agentforge.model.ModelRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import java.util.regex.Pattern;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Chat through the model gateway's chat route. The gateway does not stream yet, so this emits the
 * whole answer as one chunk rather than faking a token stream. Each chunk names the model that
 * actually answered, which after a failover is not the route's first choice.
 */
public class GatewayModelProvider implements ModelProvider {
    public static final String PROVIDER = "gateway";
    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9._:-]{1,120}");
    private final ModelGatewayClient client;
    private final ModelGatewayProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    public GatewayModelProvider(ModelGatewayClient client, ModelGatewayProperties properties) { this.client = client; this.properties = properties; }
    @Override public String provider() { return PROVIDER; }
    @Override public String model() { return properties.getChatRoute(); }
    @Override public Flux<ModelChunk> stream(ModelRequest request) {
        return Mono.fromCallable(() -> client.complete(body(request), correlationId(request), properties.getRequestTimeout()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(completion -> {
                    String served = completion.servedModel() == null ? model() : completion.servedProvider() + "/" + completion.servedModel();
                    return Flux.just(new ModelChunk(completion.content(), false, provider(), served), new ModelChunk("", true, provider(), served));
                });
    }
    private ObjectNode body(ModelRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", properties.getChatRoute());
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "user").put("content", request.message());
        return body;
    }
    /** Groups a session's calls in the gateway ledger; ids the gateway would reject get a fresh one. */
    static String correlationId(ModelRequest request) {
        String sessionId = request.sessionId();
        return sessionId != null && SESSION_ID.matcher(sessionId).matches() ? "chat-" + sessionId : "chat-" + UUID.randomUUID();
    }
}
