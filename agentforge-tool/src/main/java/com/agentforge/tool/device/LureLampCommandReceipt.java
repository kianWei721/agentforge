package com.agentforge.tool.device;

public record LureLampCommandReceipt(
        String commandId,
        String messageId,
        String status,
        String message) {

    public LureLampCommandReceipt {
        status = status == null || status.isBlank() ? "UNKNOWN" : status.trim();
        message = message == null ? "" : message;
    }
}
