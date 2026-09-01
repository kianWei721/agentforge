package com.agentforge.rag;

public record KnowledgeAnswerabilityDecision(
        int index,
        String citationId,
        boolean supported,
        String reasonCode,
        String reason) {}
