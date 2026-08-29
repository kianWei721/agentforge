package com.agentforge.app.api;

import com.agentforge.runtime.confirmation.ToolConfirmation;

public record ToolConfirmationView(
        String id,
        String requestedExecutionId,
        String consumedExecutionId,
        String agentId,
        String userId,
        String toolName,
        String summary,
        String status,
        String expiresAt,
        String approvedAt,
        String rejectedAt,
        String consumedAt,
        String createdAt,
        String updatedAt) {

    public static ToolConfirmationView from(ToolConfirmation confirmation) {
        return new ToolConfirmationView(
                confirmation.id(),
                confirmation.requestedExecutionId(),
                confirmation.consumedExecutionId(),
                confirmation.agentId(),
                confirmation.userId(),
                confirmation.toolName(),
                confirmation.summary(),
                confirmation.status().name(),
                confirmation.expiresAt().toString(),
                string(confirmation.approvedAt()),
                string(confirmation.rejectedAt()),
                string(confirmation.consumedAt()),
                confirmation.createdAt().toString(),
                confirmation.updatedAt().toString());
    }

    private static String string(java.time.Instant value) {
        return value == null ? null : value.toString();
    }
}
