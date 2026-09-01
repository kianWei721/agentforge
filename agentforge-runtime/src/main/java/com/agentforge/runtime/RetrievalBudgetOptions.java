package com.agentforge.runtime;

public record RetrievalBudgetOptions(
        boolean enabled,
        int maxCallsPerExecution) {

    public RetrievalBudgetOptions {
        if (maxCallsPerExecution < 1 || maxCallsPerExecution > 100) {
            throw new IllegalArgumentException(
                    "maxCallsPerExecution must be between 1 and 100");
        }
    }

    public static RetrievalBudgetOptions defaults() {
        return new RetrievalBudgetOptions(true, 3);
    }
}
