package com.agentforge.infrastructure.tool;

import com.agentforge.infrastructure.config.IoTDeviceStatusProperties;
import com.agentforge.infrastructure.config.IoTLureLampControlProperties;
import com.agentforge.tool.device.LureLampCommandReceipt;
import com.agentforge.tool.device.LureLampControlException;
import com.agentforge.tool.device.LureLampControlPort;
import com.agentforge.tool.device.LureLampControlTool;
import com.agentforge.tool.device.LureLampState;
import com.agentforge.tool.device.LureLampStateSnapshot;
import com.agentforge.tool.telemetry.OutboundHttpTelemetry;
import com.agentforge.tool.telemetry.ToolTelemetrySink;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class HttpLureLampControlAdapter implements LureLampControlPort {

    private static final int SUCCESS_CODE = 200;
    private static final long PERIPHERAL_STATUS_PROPERTY_ID = 169L;
    private static final String PERIPHERAL_STATUS_IDENTIFIER = "PeripheralStatus";
    private static final String TRAP_LIGHT_FIELD = "TrapLight";
    private static final String COMMAND_TYPE = "SERVICE_INVOKE";
    private static final String SERVICE_IDENTIFIER = "TrapLight";
    private static final String DOWNSTREAM_SYSTEM = "yudao-device-web";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final IoTDeviceStatusProperties sharedHttpProperties;
    private final IoTLureLampControlProperties controlProperties;

    public HttpLureLampControlAdapter(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            IoTDeviceStatusProperties sharedHttpProperties,
            IoTLureLampControlProperties controlProperties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.sharedHttpProperties = sharedHttpProperties;
        this.controlProperties = controlProperties;
        this.controlProperties.validate(sharedHttpProperties);
    }

    @Override
    public LureLampStateSnapshot readState(String deviceId) {
        requireAllowedDevice(deviceId);
        URI uri =
                buildUri(
                        controlProperties
                                .getLatestPropertiesPath()
                                .replace("{deviceId}", deviceId));
        HttpRequest request =
                commonRequest(uri)
                        .GET()
                        .build();
        try {
            HttpResponse<String> response =
                    httpClient.send(
                            request,
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return parseStateResponse(response.statusCode(), response.body(), deviceId);
        } catch (HttpTimeoutException exception) {
            throw new LureLampControlException(
                    LureLampControlException.Reason.TIMEOUT,
                    false,
                    "lure lamp state query timed out",
                    exception);
        } catch (IOException exception) {
            throw new LureLampControlException(
                    LureLampControlException.Reason.BACKEND_UNAVAILABLE,
                    false,
                    "lure lamp state query transport failure",
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LureLampControlException(
                    LureLampControlException.Reason.INTERRUPTED,
                    false,
                    "lure lamp state query interrupted",
                    exception);
        }
    }

    @Override
    public LureLampCommandReceipt invokeToggle(
            String deviceId,
            ToolTelemetrySink telemetry) {
        requireAllowedDevice(deviceId);
        ToolTelemetrySink sink = telemetry == null ? ToolTelemetrySink.noop() : telemetry;
        long startedNanos = System.nanoTime();
        HttpObservation observation = new HttpObservation();
        observation.attempts = 1;

        try {
            URI uri = buildUri(controlProperties.getCommandPath());
            String body = buildCommandBody();
            HttpRequest request =
                    commonRequest(uri)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build();

            try {
                HttpResponse<String> response =
                        httpClient.send(
                                request,
                                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                observation.httpStatus = response.statusCode();
                LureLampCommandReceipt receipt =
                        parseCommandResponse(
                                response.statusCode(),
                                response.body(),
                                observation);
                observation.outcome = "COMMAND_ACCEPTED";
                return receipt;
            } catch (HttpTimeoutException exception) {
                observation.outcome = LureLampControlException.Reason.TIMEOUT.name();
                throw new LureLampControlException(
                        LureLampControlException.Reason.TIMEOUT,
                        true,
                        "lure lamp command timed out after send attempt",
                        exception);
            } catch (IOException exception) {
                observation.outcome =
                        LureLampControlException.Reason.BACKEND_UNAVAILABLE.name();
                throw new LureLampControlException(
                        LureLampControlException.Reason.BACKEND_UNAVAILABLE,
                        true,
                        "lure lamp command transport outcome is ambiguous",
                        exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                observation.outcome = LureLampControlException.Reason.INTERRUPTED.name();
                throw new LureLampControlException(
                        LureLampControlException.Reason.INTERRUPTED,
                        true,
                        "lure lamp command was interrupted after send attempt",
                        exception);
            }
        } finally {
            sink.recordOutboundHttp(
                    new OutboundHttpTelemetry(
                            "HTTP",
                            DOWNSTREAM_SYSTEM,
                            observation.attempts,
                            observation.httpStatus,
                            observation.businessCode,
                            elapsedMillis(startedNanos),
                            observation.outcome));
        }
    }

    LureLampStateSnapshot parseStateResponse(
            int httpStatus,
            String body,
            String requestedDeviceId) {
        JsonNode root = parseSuccessEnvelope(httpStatus, body);
        JsonNode data = root.has("code") ? root.get("data") : root;
        if (data == null || !data.isArray()) {
            throw new LureLampControlException(
                    LureLampControlException.Reason.INVALID_RESPONSE,
                    false,
                    "latest properties response must contain an array");
        }

        Iterator<JsonNode> iterator = data.elements();
        while (iterator.hasNext()) {
            JsonNode item = iterator.next();
            long propertyId = item.path("propertyId").asLong(-1L);
            String identifier = text(item, "identifier");
            if (propertyId != PERIPHERAL_STATUS_PROPERTY_ID
                    && !PERIPHERAL_STATUS_IDENTIFIER.equals(identifier)) {
                continue;
            }

            JsonNode propertyDataNode = item.get("propertyData");
            JsonNode propertyData = parsePropertyData(propertyDataNode);
            JsonNode peripheral =
                    propertyData == null ? null : propertyData.get(PERIPHERAL_STATUS_IDENTIFIER);
            JsonNode trapLight =
                    peripheral == null || !peripheral.isObject()
                            ? null
                            : peripheral.get(TRAP_LIGHT_FIELD);

            LureLampState state = toState(trapLight);
            return new LureLampStateSnapshot(
                    requestedDeviceId,
                    state,
                    text(item, "updateTime"),
                    "yudao-device-web-latest-properties");
        }

        return new LureLampStateSnapshot(
                requestedDeviceId,
                LureLampState.UNKNOWN,
                null,
                "yudao-device-web-latest-properties");
    }

    LureLampCommandReceipt parseCommandResponse(
            int httpStatus,
            String body,
            HttpObservation observation) {
        if (httpStatus == 401) {
            throw commandFailure(
                    LureLampControlException.Reason.AUTHENTICATION_FAILED,
                    false,
                    httpStatus,
                    observation);
        }
        if (httpStatus == 403) {
            throw commandFailure(
                    LureLampControlException.Reason.FORBIDDEN,
                    false,
                    httpStatus,
                    observation);
        }
        if (httpStatus >= 500) {
            throw commandFailure(
                    LureLampControlException.Reason.BACKEND_UNAVAILABLE,
                    true,
                    httpStatus,
                    observation);
        }
        if (httpStatus < 200 || httpStatus >= 300) {
            throw commandFailure(
                    LureLampControlException.Reason.BAD_REQUEST,
                    false,
                    httpStatus,
                    observation);
        }

        final JsonNode root;
        try {
            root = objectMapper.readTree(body == null ? "" : body);
        } catch (Exception exception) {
            observation.outcome =
                    LureLampControlException.Reason.INVALID_RESPONSE.name();
            throw new LureLampControlException(
                    LureLampControlException.Reason.INVALID_RESPONSE,
                    true,
                    "command backend returned invalid JSON",
                    exception);
        }

        JsonNode data = root;
        if (root.has("code")) {
            int businessCode = root.path("code").asInt(-1);
            observation.businessCode = businessCode;
            if (businessCode == 401) {
                throw commandFailure(
                        LureLampControlException.Reason.AUTHENTICATION_FAILED,
                        false,
                        businessCode,
                        observation);
            }
            if (businessCode == 403) {
                throw commandFailure(
                        LureLampControlException.Reason.FORBIDDEN,
                        false,
                        businessCode,
                        observation);
            }
            if (businessCode != SUCCESS_CODE) {
                throw commandFailure(
                        LureLampControlException.Reason.COMMAND_REJECTED,
                        false,
                        businessCode,
                        observation);
            }
            data = root.get("data");
        } else {
            observation.businessCode = SUCCESS_CODE;
        }

        if (data == null || data.isNull() || !data.isObject()) {
            observation.outcome =
                    LureLampControlException.Reason.INVALID_RESPONSE.name();
            throw new LureLampControlException(
                    LureLampControlException.Reason.INVALID_RESPONSE,
                    true,
                    "command backend returned no command receipt");
        }

        String status = text(data, "status");
        if (status == null || status.isBlank()) {
            observation.outcome =
                    LureLampControlException.Reason.INVALID_RESPONSE.name();
            throw new LureLampControlException(
                    LureLampControlException.Reason.INVALID_RESPONSE,
                    true,
                    "command receipt did not contain status");
        }
        if ("FAILED".equalsIgnoreCase(status)) {
            observation.outcome =
                    LureLampControlException.Reason.COMMAND_REJECTED.name();
            throw new LureLampControlException(
                    LureLampControlException.Reason.COMMAND_REJECTED,
                    false,
                    "command backend rejected lure lamp command");
        }

        return new LureLampCommandReceipt(
                scalarText(data.get("commandId")),
                text(data, "messageId"),
                status,
                text(data, "message"));
    }

    private JsonNode parseSuccessEnvelope(int httpStatus, String body) {
        if (httpStatus == 401) {
            throw stateFailure(
                    LureLampControlException.Reason.AUTHENTICATION_FAILED);
        }
        if (httpStatus == 403) {
            throw stateFailure(LureLampControlException.Reason.FORBIDDEN);
        }
        if (httpStatus == 404) {
            throw stateFailure(LureLampControlException.Reason.DEVICE_NOT_FOUND);
        }
        if (httpStatus >= 500) {
            throw stateFailure(
                    LureLampControlException.Reason.BACKEND_UNAVAILABLE);
        }
        if (httpStatus < 200 || httpStatus >= 300) {
            throw stateFailure(LureLampControlException.Reason.BAD_REQUEST);
        }

        final JsonNode root;
        try {
            root = objectMapper.readTree(body == null ? "" : body);
        } catch (Exception exception) {
            throw new LureLampControlException(
                    LureLampControlException.Reason.INVALID_RESPONSE,
                    false,
                    "latest properties backend returned invalid JSON",
                    exception);
        }

        if (root.has("code")) {
            int businessCode = root.path("code").asInt(-1);
            if (businessCode == 401) {
                throw stateFailure(
                        LureLampControlException.Reason.AUTHENTICATION_FAILED);
            }
            if (businessCode == 403) {
                throw stateFailure(LureLampControlException.Reason.FORBIDDEN);
            }
            if (businessCode == 404) {
                throw stateFailure(
                        LureLampControlException.Reason.DEVICE_NOT_FOUND);
            }
            if (businessCode != SUCCESS_CODE) {
                throw stateFailure(LureLampControlException.Reason.BAD_REQUEST);
            }
        }
        return root;
    }

    private String buildCommandBody() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("deviceId", Long.parseLong(LureLampControlTool.ALLOWED_DEVICE_ID));
        root.put("commandType", COMMAND_TYPE);
        root.put("identifier", SERVICE_IDENTIFIER);
        root.set("params", objectMapper.createObjectNode());
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception exception) {
            throw new LureLampControlException(
                    LureLampControlException.Reason.INVALID_RESPONSE,
                    false,
                    "failed to serialize fixed lure lamp command",
                    exception);
        }
    }

    private HttpRequest.Builder commonRequest(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(controlProperties.getRequestTimeout())
                .header("Accept", "application/json")
                .header(
                        "Authorization",
                        sharedHttpProperties.authorizationHeaderValue())
                .header("tenant-id", sharedHttpProperties.getTenantId())
                .header("User-Agent", "AgentForge/0.4");
    }

    private URI buildUri(String path) {
        String base = sharedHttpProperties.getBaseUrl();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }

    private void requireAllowedDevice(String deviceId) {
        if (!controlProperties.isEnabled()) {
            throw new LureLampControlException(
                    LureLampControlException.Reason.CONTROL_DISABLED,
                    false,
                    "lure lamp control is disabled");
        }
        if (!LureLampControlTool.ALLOWED_DEVICE_ID.equals(deviceId)) {
            throw new LureLampControlException(
                    LureLampControlException.Reason.DEVICE_NOT_ALLOWED,
                    false,
                    "device is outside the fixed lure lamp control allowlist");
        }
    }

    private JsonNode parsePropertyData(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isObject()) {
            return value;
        }
        if (!value.isTextual()) {
            return null;
        }
        try {
            return objectMapper.readTree(value.asText());
        } catch (Exception exception) {
            throw new LureLampControlException(
                    LureLampControlException.Reason.INVALID_RESPONSE,
                    false,
                    "PeripheralStatus propertyData is invalid JSON",
                    exception);
        }
    }

    private static LureLampState toState(JsonNode value) {
        if (value == null || value.isNull()) {
            return LureLampState.UNKNOWN;
        }
        if (value.isBoolean()) {
            return value.booleanValue() ? LureLampState.ON : LureLampState.OFF;
        }
        if (value.isIntegralNumber()) {
            if (value.intValue() == 1) {
                return LureLampState.ON;
            }
            if (value.intValue() == 0) {
                return LureLampState.OFF;
            }
            return LureLampState.UNKNOWN;
        }
        String text = value.asText("").trim();
        if ("1".equals(text) || "true".equalsIgnoreCase(text) || "on".equalsIgnoreCase(text)) {
            return LureLampState.ON;
        }
        if ("0".equals(text) || "false".equalsIgnoreCase(text) || "off".equalsIgnoreCase(text)) {
            return LureLampState.OFF;
        }
        return LureLampState.UNKNOWN;
    }

    private static LureLampControlException stateFailure(
            LureLampControlException.Reason reason) {
        return new LureLampControlException(
                reason,
                false,
                "lure lamp state query failed: " + reason.name());
    }

    private static LureLampControlException commandFailure(
            LureLampControlException.Reason reason,
            boolean mayHaveExecuted,
            int code,
            HttpObservation observation) {
        observation.outcome = reason.name();
        return new LureLampControlException(
                reason,
                mayHaveExecuted,
                "lure lamp command backend rejected request with code " + code);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String scalarText(JsonNode value) {
        return value == null || value.isNull() ? null : value.asText();
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(
                0L,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
    }

    static final class HttpObservation {
        int attempts;
        Integer httpStatus;
        Integer businessCode;
        String outcome = "UNKNOWN";
    }
}
