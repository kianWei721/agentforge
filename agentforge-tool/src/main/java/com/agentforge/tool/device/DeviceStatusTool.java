package com.agentforge.tool.device;

import com.agentforge.tool.AgentTool;
import com.agentforge.tool.ToolInvocationContext;
import com.agentforge.tool.ToolResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DeviceStatusTool implements AgentTool {

    public static final String NAME = "query_device_status";

    private final DeviceStatusQueryPort queryPort;

    public DeviceStatusTool(DeviceStatusQueryPort queryPort) {
        this.queryPort = queryPort;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "按 deviceId 查询 IoT 设备当前状态、最后上报时间和基础运行信息。"
                + "数据通过 DeviceStatusQueryPort 获取，可切换演示适配器或真实 IoT HTTP 后端。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "deviceId",
                                Map.of(
                                        "type", "string",
                                        "description", "设备唯一标识，例如 367")),
                "required", List.of("deviceId"),
                "additionalProperties", false);
    }

    @Override
    public ToolResult execute(
            ToolInvocationContext context, Map<String, Object> arguments) {
        Object rawDeviceId = arguments.get("deviceId");
        if (rawDeviceId == null || rawDeviceId.toString().isBlank()) {
            return ToolResult.failure("deviceId is required");
        }

        String deviceId = rawDeviceId.toString().trim();
        if (!deviceId.matches("\\d{1,19}")) {
            return ToolResult.failure("deviceId must be a numeric identifier");
        }

        try {
            return queryPort
                .findByDeviceId(deviceId)
                .map(
                        snapshot -> {
                            Map<String, Object> data = new LinkedHashMap<>();
                            data.put("deviceId", snapshot.deviceId());
                            data.put("status", snapshot.status());
                            data.put("lastSeenAt", snapshot.lastSeenAt());
                            data.put("source", snapshot.source());

                            Map<String, Object> safeAttributes = new LinkedHashMap<>();
                            copyIfPresent(snapshot.attributes(), safeAttributes, "stateRaw");
                            copyIfPresent(snapshot.attributes(), safeAttributes, "deviceNickname");
                            copyIfPresent(snapshot.attributes(), safeAttributes, "productId");
                            if (!safeAttributes.isEmpty()) {
                                data.put("attributes", safeAttributes);
                            }
                            return ToolResult.success("device status queried", data);
                        })
                .orElseGet(() -> ToolResult.failure("device not found: " + deviceId));
        } catch (DeviceStatusQueryException exception) {
            return ToolResult.failure(
                    "device status query unavailable: " + exception.reason().name());
        }
    }

    private static void copyIfPresent(
            Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }
}
