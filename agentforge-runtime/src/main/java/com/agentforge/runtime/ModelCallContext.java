package com.agentforge.runtime;

/**
 * Keys the runtime puts into the Reactor context of a turn, for the model to read on each call.
 * AgentScope's model interface carries no request context of its own.
 */
public final class ModelCallContext {

    /**
     * Ties every model call of one turn, compaction included, together in the model gateway's
     * ledger: {@code ag-<executionId>}.
     */
    public static final String CORRELATION_ID = "agentforge.model.correlationId";

    private ModelCallContext() {}

    public static String correlationId(String executionId) {
        return "ag-" + executionId;
    }
}
