package com.agentforge.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Per-search context handed to model-backed stages. Its correlation id is sent to the model
 * gateway with every call, and the gateway's answers are collected here, so a search can be
 * traced into the gateway ledger and back.
 */
public final class KnowledgeModelCallContext {

    private final String correlationId;
    private final List<KnowledgeModelCall> calls = new ArrayList<>();

    public KnowledgeModelCallContext(String correlationId) {
        this.correlationId = correlationId;
    }

    public static KnowledgeModelCallContext newSearch() {
        return new KnowledgeModelCallContext("kr-" + UUID.randomUUID());
    }

    public String correlationId() {
        return correlationId;
    }

    public synchronized void record(KnowledgeModelCall call) {
        calls.add(call);
    }

    public synchronized KnowledgeModelCallDiagnostics diagnostics() {
        return new KnowledgeModelCallDiagnostics(correlationId, List.copyOf(calls));
    }
}
