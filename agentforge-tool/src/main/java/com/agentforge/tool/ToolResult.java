package com.agentforge.tool;

import java.util.LinkedHashMap;
import java.util.Map;

public record ToolResult(boolean success, String message, Map<String, Object> data) {

    public ToolResult {
        message = message == null ? "" : message;
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public static ToolResult success(String message, Map<String, Object> data) {
        return new ToolResult(true, message, data);
    }

    public static ToolResult failure(String message) {
        return new ToolResult(false, message, Map.of());
    }

    public String toModelText() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", success);
        payload.put("message", message);
        payload.put("data", data);
        return payload.toString();
    }
}
