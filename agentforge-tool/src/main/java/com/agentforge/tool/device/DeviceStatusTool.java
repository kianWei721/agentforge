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
                + "当前 V0.2 使用可替换的 DeviceStatusQueryPort，默认适配器提供演示数据。";
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
        return queryPort
                .findByDeviceId(deviceId)
                .map(
                        snapshot -> {
                            Map<String, Object> data = new LinkedHashMap<>();
                            data.put("deviceId", snapshot.deviceId());
                            data.put("status", snapshot.status());
                            data.put("lastSeenAt", snapshot.lastSeenAt());
                            data.put("source", snapshot.source());
                            data.put("attributes", snapshot.attributes());
                            return ToolResult.success("device status queried", data);
                        })
                .orElseGet(() -> ToolResult.failure("device not found: " + deviceId));
    }
}
