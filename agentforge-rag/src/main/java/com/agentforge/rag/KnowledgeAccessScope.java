package com.agentforge.rag;

public record KnowledgeAccessScope(KnowledgeVisibility visibility, String customerId) {

    public KnowledgeAccessScope {
        if (visibility == null) {
            throw new IllegalArgumentException("visibility is required");
        }
        customerId = customerId == null || customerId.isBlank() ? null : customerId.trim();
        if (visibility == KnowledgeVisibility.CUSTOMER && customerId == null) {
            throw new IllegalArgumentException("customerId is required for CUSTOMER visibility");
        }
        if (visibility != KnowledgeVisibility.CUSTOMER && customerId != null) {
            throw new IllegalArgumentException(
                    "customerId is only allowed for CUSTOMER visibility");
        }
    }

    public static KnowledgeAccessScope internal() {
        return new KnowledgeAccessScope(KnowledgeVisibility.INTERNAL, null);
    }

    public static KnowledgeAccessScope publicAudience() {
        return new KnowledgeAccessScope(KnowledgeVisibility.PUBLIC, null);
    }

    public static KnowledgeAccessScope customer(String customerId) {
        return new KnowledgeAccessScope(KnowledgeVisibility.CUSTOMER, customerId);
    }
}
