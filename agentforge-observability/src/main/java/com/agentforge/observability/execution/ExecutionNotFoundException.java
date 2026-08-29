package com.agentforge.observability.execution;

public class ExecutionNotFoundException extends RuntimeException {

    public ExecutionNotFoundException(String executionId) {
        super("Agent execution not found: " + executionId);
    }
}
