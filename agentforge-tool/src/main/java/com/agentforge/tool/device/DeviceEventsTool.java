package com.agentforge.tool.device;

import com.agentforge.tool.AgentTool;
import com.agentforge.tool.ToolInvocationContext;
import com.agentforge.tool.ToolResult;
import com.agentforge.tool.ToolRiskLevel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DeviceEventsTool implements AgentTool {
    public static final String NAME = "query_device_events";
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 20;
    private final DeviceEventsQueryPort queryPort;

    public DeviceEventsTool(DeviceEventsQueryPort queryPort) {
        this.queryPort = queryPort;
    }

    @Override public String name() { return NAME; }

    @Override
    public String description() {
        return "按 deviceId 查询最近 IoT 设备事件，用于补充设备近期异常、告警或业务事件证据。"
                + " limit 可选，默认 10，最大 20。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> limit = new LinkedHashMap<>();
        limit.put("type", "integer");
        limit.put("minimum", 1);
        limit.put("maximum", MAX_LIMIT);
        limit.put("default", DEFAULT_LIMIT);
        limit.put("description", "最多返回的最近事件数量");
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "deviceId", Map.of(
                                "type", "string",
                                "description", "设备唯一标识，例如 373"),
                        "limit", limit),
                "required", List.of("deviceId"),
                "additionalProperties", false);
    }

    @Override public ToolRiskLevel riskLevel() { return ToolRiskLevel.LOW; }

    @Override
    public ToolResult execute(ToolInvocationContext context, Map<String, Object> arguments) {
        String deviceId = numericDeviceId(arguments.get("deviceId"));
        if (deviceId == null) {
            return ToolResult.failure(
                    "deviceId must be a numeric identifier", "INVALID_ARGUMENT", false);
        }
        Integer limit = parseLimit(arguments.get("limit"));
        if (limit == null) {
            return ToolResult.failure(
                    "limit must be an integer between 1 and 20", "INVALID_ARGUMENT", false);
        }
        try {
            return queryPort.findRecentEvents(
                            deviceId, limit, context == null ? null : context.telemetry())
                    .map(DeviceEventsTool::success)
                    .orElseGet(() -> ToolResult.failure(
                            "device not found: " + deviceId, "NOT_FOUND", false));
        } catch (DeviceDiagnosticQueryException exception) {
            return ToolResult.failure(
                    "device events query unavailable: " + exception.reason().name(),
                    exception.reason().name(),
                    exception.retryable());
        }
    }

    private static ToolResult success(DeviceEventsSnapshot snapshot) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (DeviceEventRecord event : snapshot.events()) {
            Map<String, Object> item = new LinkedHashMap<>();
            if (event.eventId() != null) item.put("eventId", event.eventId());
            if (event.eventName() != null) item.put("eventName", event.eventName());
            if (event.data() != null) item.put("data", event.data());
            if (event.occurredAt() != null) item.put("occurredAt", event.occurredAt());
            events.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deviceId", snapshot.deviceId());
        data.put("total", snapshot.total());
        data.put("count", events.size());
        data.put("events", events);
        data.put("source", snapshot.source());
        return ToolResult.success("device events queried", data);
    }

    private static String numericDeviceId(Object raw) {
        if (raw == null) return null;
        String value = raw.toString().trim();
        return value.matches("\\d{1,19}") ? value : null;
    }

    private static Integer parseLimit(Object raw) {
        if (raw == null) return DEFAULT_LIMIT;
        try {
            int value;
            if (raw instanceof Number number) {
                double numeric = number.doubleValue();
                if (numeric != Math.rint(numeric)) return null;
                value = number.intValue();
            } else {
                String text = raw.toString().trim();
                if (!text.matches("\\d+")) return null;
                value = Integer.parseInt(text);
            }
            return value >= 1 && value <= MAX_LIMIT ? value : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
