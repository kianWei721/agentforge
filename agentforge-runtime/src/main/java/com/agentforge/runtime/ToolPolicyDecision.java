package com.agentforge.runtime;

public enum ToolPolicyDecision {
    ALLOW,
    DENY,
    REQUIRE_CONFIRMATION;

    public boolean executable() {
        return this == ALLOW;
    }
}
