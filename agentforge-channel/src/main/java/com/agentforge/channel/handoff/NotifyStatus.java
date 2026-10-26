package com.agentforge.channel.handoff;

/**
 * Where the staff-group notice for a hand-off stands. DISABLED: no webhook was configured when it
 * opened; the ticket is still listed by the API. CANCELLED: resolved before the notice went out.
 */
public enum NotifyStatus {
    PENDING,
    SENT,
    FAILED,
    DISABLED,
    CANCELLED
}
