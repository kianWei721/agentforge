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

public class DevicePropertiesTool implements AgentTool {
    public static final String NAME = "query_device_properties";
    private final DevicePropertiesQueryPort queryPort;

    public DevicePropertiesTool(DevicePropertiesQueryPort queryPort) {
        this.queryPort = queryPort;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "按 deviceId 查询 IoT 设备最近保存的属性/物模型快照。"
                + " queriedAt 是本次 Tool 查询时间；snapshotUpdatedAt 和各属性 observedAt 都是历史快照时间，"
                + "不能当作当前时间。source=DB_LOAD 表示从平台数据库加载。"
                + "shadowStatus=CONFLICT 只表示影子状态存在不一致，不能单独推断掉线原因或配置同步中断。";
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
                                        "description", "设备唯一标识，例如 373")),
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
        String deviceId = numericDeviceId(arguments.get("deviceId"));
        if (deviceId == null) {
            return ToolResult.failure(
                    "deviceId must be a numeric identifier", "INVALID_ARGUMENT", false);
        }
        try {
            return queryPort
                    .findByDeviceId(
                            deviceId, context == null ? null : context.telemetry())
                    .map(DevicePropertiesTool::success)
                    .orElseGet(
                            () ->
                                    ToolResult.failure(
                                            "device not found: " + deviceId,
                                            "NOT_FOUND",
                                            false));
        } catch (DeviceDiagnosticQueryException exception) {
            return ToolResult.failure(
                    "device properties query unavailable: " + exception.reason().name(),
                    exception.reason().name(),
                    exception.retryable());
        }
    }

    private static ToolResult success(DevicePropertiesSnapshot snapshot) {
        Instant queriedAt = Instant.now();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deviceId", snapshot.deviceId());
        data.put("queriedAt", queriedAt.toString());
        put(data, "deviceNickname", snapshot.deviceNickname());
        put(data, "productId", snapshot.productId());
        put(data, "snapshotUpdatedAt", snapshot.observedAt());
        Long snapshotAgeSeconds = ageSeconds(snapshot.observedAt(), queriedAt);
        if (snapshotAgeSeconds != null) {
            data.put("snapshotAgeSeconds", snapshotAgeSeconds);
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        snapshot.properties().forEach(
                (identifier, value) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    put(item, "name", value.name());
                    if (value.value() != null) {
                        item.put("value", value.value());
                    }
                    put(item, "observedAt", value.observedAt());
                    put(item, "source", value.source());
                    put(item, "shadowStatus", value.shadowStatus());
                    properties.put(identifier, item);
                });
        data.put("properties", properties);
        data.put("propertyCount", properties.size());
        data.put("source", snapshot.source());
        data.put(
                "propertySemantics",
                "LAST_SAVED_SNAPSHOT_NOT_LIVE_TELEMETRY_UNLESS_TIMESTAMPS_ARE_FRESH");
        return ToolResult.success("device properties queried", data);
    }

    private static Long ageSeconds(String observedAt, Instant queriedAt) {
        if (observedAt == null || observedAt.isBlank()) {
            return null;
        }
        try {
            long seconds = Duration.between(Instant.parse(observedAt), queriedAt).getSeconds();
            return seconds >= 0 ? seconds : null;
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static String numericDeviceId(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.toString().trim();
        return value.matches("\\d{1,19}") ? value : null;
    }

    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
