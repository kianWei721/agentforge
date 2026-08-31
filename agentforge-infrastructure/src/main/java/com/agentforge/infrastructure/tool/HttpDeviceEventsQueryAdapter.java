package com.agentforge.infrastructure.tool;

import com.agentforge.infrastructure.config.IoTDeviceStatusProperties;
import com.agentforge.tool.device.DeviceDiagnosticQueryException;
import com.agentforge.tool.device.DeviceEventRecord;
import com.agentforge.tool.device.DeviceEventsQueryPort;
import com.agentforge.tool.device.DeviceEventsSnapshot;
import com.agentforge.tool.telemetry.ToolTelemetrySink;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class HttpDeviceEventsQueryAdapter implements DeviceEventsQueryPort {
    private static final String SOURCE = "yudao-device-web-http";
    private final IoTDeviceStatusProperties properties;
    private final YudaoDeviceReadHttpSupport http;

    public HttpDeviceEventsQueryAdapter(
            HttpClient httpClient, ObjectMapper objectMapper, IoTDeviceStatusProperties properties) {
        this.properties = properties;
        this.http = new YudaoDeviceReadHttpSupport(httpClient, objectMapper, properties);
    }

    @Override
    public Optional<DeviceEventsSnapshot> findRecentEvents(String deviceId, int limit) {
        return findRecentEvents(deviceId, limit, ToolTelemetrySink.noop());
    }

    @Override
    public Optional<DeviceEventsSnapshot> findRecentEvents(
            String deviceId, int limit, ToolTelemetrySink telemetry) {
        validate(deviceId, limit);

        String details = properties.getDetailsPath().replace("{deviceId}", deviceId);
        Optional<JsonNode> guard = http.getData(details, telemetry);
        if (guard.isEmpty()) return Optional.empty();

        String guardedDeviceId = YudaoDeviceReadHttpSupport.text(guard.get(), "deviceId");
        if (guardedDeviceId != null
                && !guardedDeviceId.isBlank()
                && !deviceId.equals(guardedDeviceId)) {
            throw new DeviceDiagnosticQueryException(
                    DeviceDiagnosticQueryException.Reason.INVALID_RESPONSE, false,
                    "IoT backend returned a different deviceId");
        }

        String history = properties.getEventsHistoryPath().replace("{deviceId}", deviceId)
                + "?pageNo=1&pageSize=" + limit;
        return http.getData(history, telemetry).map(data -> parse(data, deviceId));
    }

    private DeviceEventsSnapshot parse(JsonNode data, String requestedDeviceId) {
        long total = data.path("total").asLong(0L);
        List<DeviceEventRecord> events = new ArrayList<>();
        JsonNode list = data.get("list");
        if (list != null && list.isArray()) {
            for (JsonNode item : list) {
                String responseDeviceId = YudaoDeviceReadHttpSupport.text(item, "deviceId");
                if (responseDeviceId != null
                        && !responseDeviceId.isBlank()
                        && !requestedDeviceId.equals(responseDeviceId)) {
                    throw new DeviceDiagnosticQueryException(
                            DeviceDiagnosticQueryException.Reason.INVALID_RESPONSE, false,
                            "IoT backend returned event data for a different deviceId");
                }
                Long eventId = item.path("eventId").canConvertToLong()
                        ? item.path("eventId").longValue() : null;
                events.add(new DeviceEventRecord(
                        eventId,
                        YudaoDeviceReadHttpSupport.text(item, "eventName"),
                        http.toJavaValue(item.get("eventData")),
                        YudaoDeviceReadHttpSupport.normalizeTime(item.get("createTime"))));
            }
        }
        return new DeviceEventsSnapshot(requestedDeviceId, total, events, SOURCE);
    }

    private static void validate(String deviceId, int limit) {
        if (deviceId == null || !deviceId.matches("\\d{1,19}")) {
            throw new DeviceDiagnosticQueryException(
                    DeviceDiagnosticQueryException.Reason.BAD_REQUEST, false,
                    "deviceId must be numeric");
        }
        if (limit < 1 || limit > 20) {
            throw new DeviceDiagnosticQueryException(
                    DeviceDiagnosticQueryException.Reason.BAD_REQUEST, false,
                    "limit must be between 1 and 20");
        }
    }
}
