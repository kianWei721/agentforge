package com.agentforge.runtime;

public record ToolExecutionIdentity(String executionId) {

    public ToolExecutionIdentity {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("executionId must not be blank");
        }
        executionId = executionId.trim();
    }
}
