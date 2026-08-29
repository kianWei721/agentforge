package com.agentforge.runtime.confirmation;

public enum ToolConfirmationStatus {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED,
    CONSUMED;

    public boolean active() {
        return this == PENDING || this == APPROVED;
    }

    public boolean terminal() {
        return !active();
    }
}
