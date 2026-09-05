package com.agentforge.infrastructure.tool;

import com.agentforge.infrastructure.config.IoTDeviceStatusProperties;
import com.agentforge.tool.device.DeviceDiagnosticQueryException;
import com.agentforge.tool.device.DevicePropertiesQueryPort;
import com.agentforge.tool.device.DevicePropertiesSnapshot;
import com.agentforge.tool.device.DevicePropertyValue;
import com.agentforge.tool.telemetry.ToolTelemetrySink;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class HttpDevicePropertiesQueryAdapter implements DevicePropertiesQueryPort {
    private static final String SOURCE = "external-iot-http";
    private static final java.util.Set<String> SENSITIVE_IDENTIFIERS =
            java.util.Set.of("GeoLocation", "ManualGeoLocation", "ProduceInfo");
    private final IoTDeviceStatusProperties properties;
    private final YudaoDeviceReadHttpSupport http;

    public HttpDevicePropertiesQueryAdapter(
            HttpClient httpClient, ObjectMapper objectMapper, IoTDeviceStatusProperties properties) {
        this.properties = properties;
        this.http = new YudaoDeviceReadHttpSupport(httpClient, objectMapper, properties);
    }

    @Override
    public Optional<DevicePropertiesSnapshot> findByDeviceId(String deviceId) {
        return findByDeviceId(deviceId, ToolTelemetrySink.noop());
    }

    @Override
    public Optional<DevicePropertiesSnapshot> findByDeviceId(
            String deviceId, ToolTelemetrySink telemetry) {
        validate(deviceId);
        String path = properties.getDetailsPath().replace("{deviceId}", deviceId);
        return http.getData(path, telemetry).map(data -> parse(data, deviceId));
    }

    private DevicePropertiesSnapshot parse(JsonNode data, String requestedDeviceId) {
        String responseDeviceId = YudaoDeviceReadHttpSupport.text(data, "deviceId");
        if (responseDeviceId == null || responseDeviceId.isBlank()) responseDeviceId = requestedDeviceId;
        if (!requestedDeviceId.equals(responseDeviceId)) {
            throw new DeviceDiagnosticQueryException(
                    DeviceDiagnosticQueryException.Reason.INVALID_RESPONSE, false,
                    "IoT backend returned a different deviceId");
        }

        Map<String, DevicePropertyValue> values = new LinkedHashMap<>();
        JsonNode propertiesNode = data.get("properties");
        if (propertiesNode != null && propertiesNode.isArray()) {
            for (JsonNode item : propertiesNode) {
                JsonNode model = item.get("model");
                String identifier = YudaoDeviceReadHttpSupport.text(model, "identifier");
                if (identifier == null
                        || identifier.isBlank()
                        || SENSITIVE_IDENTIFIERS.contains(identifier)) {
                    continue;
                }
                values.put(identifier, new DevicePropertyValue(
                        YudaoDeviceReadHttpSupport.text(model, "propertyName"),
                        http.toJavaValue(item.get("latestValue")),
                        YudaoDeviceReadHttpSupport.normalizeTime(item.get("latestTime")),
                        YudaoDeviceReadHttpSupport.text(item, "source"),
                        YudaoDeviceReadHttpSupport.text(item, "shadowStatus")));
            }
        }

        Long productId = data.path("productId").canConvertToLong()
                ? data.path("productId").longValue() : null;
        return new DevicePropertiesSnapshot(
                responseDeviceId,
                YudaoDeviceReadHttpSupport.text(data, "deviceNickname"),
                productId,
                YudaoDeviceReadHttpSupport.normalizeTime(data.get("latestUpdateTime")),
                SOURCE,
                values);
    }

    private static void validate(String deviceId) {
        if (deviceId == null || !deviceId.matches("\\d{1,19}")) {
            throw new DeviceDiagnosticQueryException(
                    DeviceDiagnosticQueryException.Reason.BAD_REQUEST, false,
                    "deviceId must be numeric");
        }
    }
}
