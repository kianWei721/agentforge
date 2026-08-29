package com.agentforge.runtime;

import java.util.Objects;

public record ToolPolicyEvaluation(ToolPolicyDecision decision, String reason) {

    public ToolPolicyEvaluation {
        Objects.requireNonNull(decision, "decision must not be null");
        reason = reason == null || reason.isBlank() ? "unspecified" : reason.trim();
    }

    public static ToolPolicyEvaluation allow(String reason) {
        return new ToolPolicyEvaluation(ToolPolicyDecision.ALLOW, reason);
    }

    public static ToolPolicyEvaluation deny(String reason) {
        return new ToolPolicyEvaluation(ToolPolicyDecision.DENY, reason);
    }

    public static ToolPolicyEvaluation requireConfirmation(String reason) {
        return new ToolPolicyEvaluation(ToolPolicyDecision.REQUIRE_CONFIRMATION, reason);
    }
}
