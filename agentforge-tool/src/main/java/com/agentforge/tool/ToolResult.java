package com.agentforge.tool;

import java.util.LinkedHashMap;
import java.util.Map;

public record ToolResult(
        boolean success,
        String message,
        Map<String, Object> data,
        String failureCode,
        boolean retryable) {

    public ToolResult(boolean success, String message, Map<String, Object> data) {
        this(success, message, data, null, false);
    }

    public ToolResult {
        message = message == null ? "" : message;
        data = data == null ? Map.of() : Map.copyOf(data);
        if (success) {
            failureCode = null;
            retryable = false;
        } else {
            failureCode =
                    failureCode == null || failureCode.isBlank()
                            ? "TOOL_FAILURE"
                            : failureCode.trim();
        }
    }

    public static ToolResult success(String message, Map<String, Object> data) {
        return new ToolResult(true, message, data, null, false);
    }

    public static ToolResult failure(String message) {
        return failure(message, "TOOL_FAILURE", false);
    }

    public static ToolResult failure(String message, String failureCode, boolean retryable) {
        return new ToolResult(false, message, Map.of(), failureCode, retryable);
    }

    public String toModelText() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", success);
        payload.put("message", message);
        payload.put("data", data);
        return payload.toString();
    }
}
