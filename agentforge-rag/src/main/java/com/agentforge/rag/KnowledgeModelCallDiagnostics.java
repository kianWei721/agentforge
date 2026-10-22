package com.agentforge.rag;

import java.util.List;

/** The correlation id of one search and the gateway calls it made; empty calls in direct mode. */
public record KnowledgeModelCallDiagnostics(String correlationId, List<KnowledgeModelCall> calls) {}
