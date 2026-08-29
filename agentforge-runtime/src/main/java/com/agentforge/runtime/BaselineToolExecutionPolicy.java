package com.agentforge.runtime;

import com.agentforge.tool.ToolRiskLevel;
import java.util.Objects;

public final class BaselineToolExecutionPolicy implements ToolExecutionPolicy {

    private final ToolPolicyOptions options;

    public BaselineToolExecutionPolicy(ToolPolicyOptions options) {
        this.options = options == null ? ToolPolicyOptions.defaults() : options;
    }

    @Override
    public ToolPolicyEvaluation evaluate(ToolPolicyContext context) {
        Objects.requireNonNull(context, "context must not be null");

        if (!options.enabled()) {
            return ToolPolicyEvaluation.allow("policy_disabled");
        }
        if (!context.readOnly() && options.requireConfirmationForWriteTools()) {
            return ToolPolicyEvaluation.requireConfirmation(
                    "write_tool_requires_confirmation");
        }
        if (context.readOnly()
                && context.riskLevel() == ToolRiskLevel.HIGH
                && options.requireConfirmationForHighRiskReadOnly()) {
            return ToolPolicyEvaluation.requireConfirmation(
                    "high_risk_read_requires_confirmation");
        }
        return ToolPolicyEvaluation.allow("baseline_allow");
    }
}
