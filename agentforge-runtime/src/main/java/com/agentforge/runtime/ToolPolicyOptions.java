package com.agentforge.runtime;

public record ToolPolicyOptions(
        boolean enabled,
        boolean requireConfirmationForWriteTools,
        boolean requireConfirmationForHighRiskReadOnly) {

    public static ToolPolicyOptions defaults() {
        return new ToolPolicyOptions(true, true, true);
    }
}
