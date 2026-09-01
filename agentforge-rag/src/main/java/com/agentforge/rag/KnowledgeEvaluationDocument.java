package com.agentforge.rag;

public record KnowledgeEvaluationDocument(
        String id,
        String title,
        KnowledgeSourceType sourceType,
        String sourceRef,
        String content) {}
