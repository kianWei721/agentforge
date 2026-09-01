package com.agentforge.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentforge.retrieval-budget")
public class RetrievalBudgetProperties {

    private boolean enabled = true;
    private int maxCallsPerExecution = 3;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxCallsPerExecution() {
        return maxCallsPerExecution;
    }

    public void setMaxCallsPerExecution(int maxCallsPerExecution) {
        this.maxCallsPerExecution = maxCallsPerExecution;
    }

    public void validate() {
        if (maxCallsPerExecution < 1 || maxCallsPerExecution > 100) {
            throw new IllegalArgumentException(
                    "agentforge.retrieval-budget.max-calls-per-execution must be between 1 and 100");
        }
    }
}
