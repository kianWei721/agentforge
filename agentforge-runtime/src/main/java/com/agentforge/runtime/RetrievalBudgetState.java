package com.agentforge.runtime;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class RetrievalBudgetState {

    static final String TARGET_TOOL_NAME = "search_knowledge";
    static final String DECISION_ALLOW = "ALLOW";
    static final String DECISION_BLOCK = "BLOCK";
    static final String DECISION_SKIP = "SKIP";
    static final String REASON_WITHIN_BUDGET = "within_budget";
    static final String REASON_BUDGET_EXHAUSTED = "retrieval_budget_exhausted";
    static final String REASON_BUDGET_DISABLED = "retrieval_budget_disabled";
    static final String FAILURE_RETRIEVAL_BUDGET_EXHAUSTED =
            "RETRIEVAL_BUDGET_EXHAUSTED";

    private final RetrievalBudgetOptions options;
    private final ConcurrentMap<String, RetrievalBudgetDecision> decisionsByCallId =
            new ConcurrentHashMap<>();
    private int usedCalls;

    RetrievalBudgetState(RetrievalBudgetOptions options) {
        this.options = options == null ? RetrievalBudgetOptions.defaults() : options;
    }

    synchronized RetrievalBudgetDecision beforeInvocation(
            String toolCallId,
            String toolName) {
        if (!TARGET_TOOL_NAME.equals(toolName)) {
            return null;
        }

        RetrievalBudgetDecision existing = decision(toolCallId);
        if (existing != null) {
            return existing;
        }

        int limit = options.maxCallsPerExecution();
        RetrievalBudgetDecision decision;
        if (!options.enabled()) {
            decision =
                    RetrievalBudgetDecision.skip(
                            REASON_BUDGET_DISABLED,
                            limit,
                            usedCalls,
                            Math.max(0, limit - usedCalls));
        } else if (usedCalls >= limit) {
            decision =
                    RetrievalBudgetDecision.block(
                            REASON_BUDGET_EXHAUSTED,
                            limit,
                            usedCalls,
                            0);
        } else {
            usedCalls++;
            decision =
                    RetrievalBudgetDecision.allow(
                            limit,
                            usedCalls,
                            Math.max(0, limit - usedCalls));
        }

        remember(toolCallId, decision);
        return decision;
    }

    synchronized RetrievalBudgetDecision skipped(
            String toolCallId,
            String toolName,
            String reason) {
        if (!TARGET_TOOL_NAME.equals(toolName)) {
            return null;
        }

        RetrievalBudgetDecision existing = decision(toolCallId);
        if (existing != null) {
            return existing;
        }

        int limit = options.maxCallsPerExecution();
        RetrievalBudgetDecision decision =
                RetrievalBudgetDecision.skip(
                        reason == null || reason.isBlank() ? "preempted" : reason,
                        limit,
                        usedCalls,
                        Math.max(0, limit - usedCalls));
        remember(toolCallId, decision);
        return decision;
    }

    RetrievalBudgetDecision decision(String toolCallId) {
        return toolCallId == null || toolCallId.isBlank()
                ? null
                : decisionsByCallId.get(toolCallId);
    }

    synchronized int usedCalls() {
        return usedCalls;
    }

    private void remember(
            String toolCallId,
            RetrievalBudgetDecision decision) {
        if (toolCallId != null && !toolCallId.isBlank()) {
            decisionsByCallId.put(toolCallId, decision);
        }
    }

    record RetrievalBudgetDecision(
            boolean allowed,
            String decision,
            String reason,
            int limit,
            int used,
            int remaining) {

        static RetrievalBudgetDecision allow(
                int limit,
                int used,
                int remaining) {
            return new RetrievalBudgetDecision(
                    true,
                    DECISION_ALLOW,
                    REASON_WITHIN_BUDGET,
                    limit,
                    used,
                    remaining);
        }

        static RetrievalBudgetDecision block(
                String reason,
                int limit,
                int used,
                int remaining) {
            return new RetrievalBudgetDecision(
                    false,
                    DECISION_BLOCK,
                    reason,
                    limit,
                    used,
                    remaining);
        }

        static RetrievalBudgetDecision skip(
                String reason,
                int limit,
                int used,
                int remaining) {
            return new RetrievalBudgetDecision(
                    true,
                    DECISION_SKIP,
                    reason,
                    limit,
                    used,
                    remaining);
        }
    }
}
