package com.agentforge.tool.device;

import com.agentforge.tool.AgentTool;
import com.agentforge.tool.ToolInvocationContext;
import com.agentforge.tool.ToolResult;
import com.agentforge.tool.ToolRiskLevel;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
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
        return "按 deviceId 查询 IoT 设备当前在线状态和最后上报时间。"
                + " queriedAt 是 AgentForge 本次查询时间；lastSeenAt 是后端最后观测时间；"
                + " lastSeenAgeSeconds 由 Tool 确定性计算，诊断时优先使用该字段判断数据新鲜度。";
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
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.LOW;
    }

    @Override
    public ToolResult execute(
            ToolInvocationContext context, Map<String, Object> arguments) {
        Object rawDeviceId = arguments.get("deviceId");
        if (rawDeviceId == null || rawDeviceId.toString().isBlank()) {
            return ToolResult.failure("deviceId is required", "INVALID_ARGUMENT", false);
        }

        String deviceId = rawDeviceId.toString().trim();
        if (!deviceId.matches("\\d{1,19}")) {
            return ToolResult.failure(
                    "deviceId must be a numeric identifier", "INVALID_ARGUMENT", false);
        }

        try {
            return queryPort
                    .findByDeviceId(deviceId, context == null ? null : context.telemetry())
                    .map(
                            snapshot -> {
                                Instant queriedAt = Instant.now();
                                Map<String, Object> data = new LinkedHashMap<>();
                                data.put("deviceId", snapshot.deviceId());
                                data.put("status", snapshot.status());
                                data.put("queriedAt", queriedAt.toString());
                                if (snapshot.lastSeenAt() != null) {
                                    data.put("lastSeenAt", snapshot.lastSeenAt());
                                    Long ageSeconds =
                                            ageSeconds(snapshot.lastSeenAt(), queriedAt);
                                    if (ageSeconds != null) {
                                        data.put("lastSeenAgeSeconds", ageSeconds);
                                    }
                                }
                                data.put("source", snapshot.source());

                                Map<String, Object> safeAttributes = new LinkedHashMap<>();
                                copyIfPresent(snapshot.attributes(), safeAttributes, "stateRaw");
                                copyIfPresent(
                                        snapshot.attributes(), safeAttributes, "deviceNickname");
                                copyIfPresent(snapshot.attributes(), safeAttributes, "productId");
                                if (!safeAttributes.isEmpty()) {
                                    data.put("attributes", safeAttributes);
                                }
                                return ToolResult.success("device status queried", data);
                            })
                    .orElseGet(
                            () ->
                                    ToolResult.failure(
                                            "device not found: " + deviceId,
                                            "NOT_FOUND",
                                            false));
        } catch (DeviceStatusQueryException exception) {
            return ToolResult.failure(
                    "device status query unavailable: " + exception.reason().name(),
                    exception.reason().name(),
                    exception.retryable());
        }
    }

    private static Long ageSeconds(String observedAt, Instant queriedAt) {
        try {
            long seconds = Duration.between(Instant.parse(observedAt), queriedAt).getSeconds();
            return seconds >= 0 ? seconds : null;
        } catch (DateTimeParseException exception) {
            return null;
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
