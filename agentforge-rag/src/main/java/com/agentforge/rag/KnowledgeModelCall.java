package com.agentforge.rag;

/**
 * One model call made while answering a knowledge search, as reported by the model gateway.
 *
 * @param stage {@code QUERY_REWRITE} or {@code ANSWERABILITY}
 * @param errorCode the gateway's error code, or null when the call succeeded
 */
public record KnowledgeModelCall(
        String stage,
        String gatewayRequestId,
        String servedProvider,
        String servedModel,
        String errorCode) {}
