package com.agentforge.runtime;

@FunctionalInterface
public interface ToolExecutionPolicy {

    ToolPolicyEvaluation evaluate(ToolPolicyContext context);
}
