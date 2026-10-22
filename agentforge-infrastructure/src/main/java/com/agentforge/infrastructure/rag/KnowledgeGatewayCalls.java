package com.agentforge.infrastructure.rag;

import com.agentforge.core.model.ModelCallFailedException;
import com.agentforge.infrastructure.gateway.ModelGatewayClient;
import com.agentforge.rag.KnowledgeModelCall;
import com.agentforge.rag.KnowledgeModelCallContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;

/** Sends one knowledge-stage call through the model gateway and records it, failed or not. */
final class KnowledgeGatewayCalls {

    private KnowledgeGatewayCalls() {}

    static JsonNode complete(
            ModelGatewayClient gateway,
            String stage,
            ObjectNode body,
            KnowledgeModelCallContext context,
            Duration timeout) {
        KnowledgeModelCallContext calls =
                context == null ? KnowledgeModelCallContext.newSearch() : context;
        try {
            ModelGatewayClient.Completion completion =
                    gateway.complete(body, calls.correlationId(), timeout);
            calls.record(
                    new KnowledgeModelCall(
                            stage,
                            completion.gatewayRequestId(),
                            completion.servedProvider(),
                            completion.servedModel(),
                            null));
            return completion.body();
        } catch (ModelCallFailedException failure) {
            calls.record(
                    new KnowledgeModelCall(
                            stage, failure.gatewayRequestId(), null, null, failure.errorCode()));
            throw failure;
        }
    }
}
