package com.agentforge.infrastructure.tool;

import com.agentforge.infrastructure.config.IoTDeviceStatusProperties;
import com.agentforge.tool.device.DeviceStatusQueryException;
import com.agentforge.tool.device.DeviceStatusQueryPort;
import com.agentforge.tool.device.DeviceStatusSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class HttpDeviceStatusQueryAdapter implements DeviceStatusQueryPort {

    private static final int SUCCESS_CODE = 200;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final IoTDeviceStatusProperties properties;
    private final SimpleCircuitBreaker circuitBreaker;

    public HttpDeviceStatusQueryAdapter(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            IoTDeviceStatusProperties properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.properties.validateForHttp();
        this.circuitBreaker =
                new SimpleCircuitBreaker(
                        properties.getCircuitBreakerFailureThreshold(),
                        properties.getCircuitBreakerOpenDuration());
    }

    @Override
    public Optional<DeviceStatusSnapshot> findByDeviceId(String deviceId) {
        if (deviceId == null || !deviceId.matches("\\d{1,19}")) {
            throw new DeviceStatusQueryException(
                    DeviceStatusQueryException.Reason.BAD_REQUEST,
                    false,
                    "deviceId must be numeric");
        }

        circuitBreaker.beforeCall();

        DeviceStatusQueryException lastFailure = null;
        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            try {
                Optional<DeviceStatusSnapshot> result = executeOnce(deviceId);
                circuitBreaker.recordSuccess();
                return result;
            } catch (DeviceStatusQueryException exception) {
                lastFailure = exception;
                if (!exception.retryable()) {
                    circuitBreaker.recordSuccess();
                    throw exception;
                }
                if (attempt < properties.getMaxAttempts()) {
                    sleepBeforeRetry();
                }
            }
        }

        circuitBreaker.recordFailure();
        throw lastFailure == null
                ? new DeviceStatusQueryException(
                        DeviceStatusQueryException.Reason.BACKEND_UNAVAILABLE,
                        true,
                        "IoT backend request failed")
                : lastFailure;
    }

    private Optional<DeviceStatusSnapshot> executeOnce(String deviceId) {
        URI uri = buildDeviceUri(deviceId);
        HttpRequest request =
                HttpRequest.newBuilder(uri)
                        .timeout(properties.getRequestTimeout())
                        .header("Accept", "application/json")
                        .header("Authorization", properties.authorizationHeaderValue())
                        .header("tenant-id", properties.getTenantId())
                        .header("User-Agent", "AgentForge/0.3")
                        .GET()
                        .build();

        try {
            HttpResponse<String> response =
                    httpClient.send(
                            request,
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return parseResponse(response.statusCode(), response.body(), deviceId);
        } catch (HttpTimeoutException exception) {
            throw new DeviceStatusQueryException(
                    DeviceStatusQueryException.Reason.TIMEOUT,
                    true,
                    "IoT backend request timed out",
                    exception);
        } catch (IOException exception) {
            throw new DeviceStatusQueryException(
                    DeviceStatusQueryException.Reason.BACKEND_UNAVAILABLE,
                    true,
                    "IoT backend transport failure",
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DeviceStatusQueryException(
                    DeviceStatusQueryException.Reason.INTERRUPTED,
                    false,
                    "IoT backend request interrupted",
                    exception);
        }
    }

    Optional<DeviceStatusSnapshot> parseResponse(
            int httpStatus, String body, String requestedDeviceId) {
        if (httpStatus == 401) {
            throw failure(DeviceStatusQueryException.Reason.AUTHENTICATION_FAILED, false, httpStatus);
        }
        if (httpStatus == 403) {
            throw failure(DeviceStatusQueryException.Reason.FORBIDDEN, false, httpStatus);
        }
        if (httpStatus == 404) {
            return Optional.empty();
        }
        if (httpStatus >= 500) {
            throw failure(DeviceStatusQueryException.Reason.BACKEND_UNAVAILABLE, true, httpStatus);
        }
        if (httpStatus < 200 || httpStatus >= 300) {
            throw failure(DeviceStatusQueryException.Reason.BAD_REQUEST, false, httpStatus);
        }

        final JsonNode root;
        try {
            root = objectMapper.readTree(body == null ? "" : body);
        } catch (Exception exception) {
            throw new DeviceStatusQueryException(
                    DeviceStatusQueryException.Reason.INVALID_RESPONSE,
                    false,
                    "IoT backend returned invalid JSON",
                    exception);
        }

        int businessCode = root.path("code").asInt(-1);
        if (businessCode == 401) {
            throw failure(DeviceStatusQueryException.Reason.AUTHENTICATION_FAILED, false, businessCode);
        }
        if (businessCode == 403) {
            throw failure(DeviceStatusQueryException.Reason.FORBIDDEN, false, businessCode);
        }
        if (businessCode == 404) {
            return Optional.empty();
        }
        if (businessCode == 500 || businessCode == 503) {
            throw failure(DeviceStatusQueryException.Reason.BACKEND_UNAVAILABLE, true, businessCode);
        }
        if (businessCode != SUCCESS_CODE) {
            throw failure(DeviceStatusQueryException.Reason.BAD_REQUEST, false, businessCode);
        }

        JsonNode data = root.get("data");
        if (data == null || data.isNull()) {
            return Optional.empty();
        }

        String responseDeviceId = text(data, "deviceId");
        if (responseDeviceId == null || responseDeviceId.isBlank()) {
            responseDeviceId = requestedDeviceId;
        }
        if (!requestedDeviceId.equals(responseDeviceId)) {
            throw new DeviceStatusQueryException(
                    DeviceStatusQueryException.Reason.INVALID_RESPONSE,
                    false,
                    "IoT backend returned a different deviceId");
        }

        String rawState = text(data, "state");
        String normalizedState = normalizeState(rawState);
        String lastSeenAt = text(data, "lastReceiveTime");

        Map<String, Object> attributes = new LinkedHashMap<>();
        put(attributes, "stateRaw", rawState);
        put(attributes, "deviceName", text(data, "deviceName"));
        put(attributes, "deviceNickname", text(data, "deviceNickname"));
        put(attributes, "productId", scalar(data.get("productId")));
        put(attributes, "location", text(data, "location"));
        put(attributes, "province", text(data, "province"));
        put(attributes, "city", text(data, "city"));
        put(attributes, "county", text(data, "county"));
        put(attributes, "longitude", scalar(data.get("longitude")));
        put(attributes, "latitude", scalar(data.get("latitude")));
        put(attributes, "autoLocationEnabled", scalar(data.get("autoLocationEnabled")));
        put(attributes, "locationSource", text(data, "locationSource"));

        return Optional.of(
                new DeviceStatusSnapshot(
                        responseDeviceId,
                        normalizedState,
                        lastSeenAt,
                        "yudao-device-mini-http",
                        attributes));
    }

    static String normalizeState(String rawState) {
        if (rawState == null || rawState.isBlank()) {
            return "UNKNOWN";
        }
        return switch (rawState.trim()) {
            case "在线" -> "ONLINE";
            case "离线" -> "OFFLINE";
            case "休眠" -> "SLEEP";
            case "未激活" -> "INACTIVE";
            default -> rawState.trim().toUpperCase(java.util.Locale.ROOT);
        };
    }

    private URI buildDeviceUri(String deviceId) {
        String base = properties.getBaseUrl();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String path = properties.getDetailPath().replace("{deviceId}", deviceId);
        return URI.create(base + path);
    }

    private void sleepBeforeRetry() {
        long millis = properties.getRetryBackoff().toMillis();
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DeviceStatusQueryException(
                    DeviceStatusQueryException.Reason.INTERRUPTED,
                    false,
                    "IoT backend retry interrupted",
                    exception);
        }
    }

    private static DeviceStatusQueryException failure(
            DeviceStatusQueryException.Reason reason, boolean retryable, int code) {
        return new DeviceStatusQueryException(
                reason, retryable, "IoT backend rejected request with code " + code);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Object scalar(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isIntegralNumber()) {
            return value.longValue();
        }
        if (value.isFloatingPointNumber()) {
            return value.doubleValue();
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        return value.asText();
    }

    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static final class SimpleCircuitBreaker {
        private final int failureThreshold;
        private final long openDurationMillis;
        private final AtomicInteger consecutiveFailures = new AtomicInteger();
        private final AtomicLong openUntilEpochMillis = new AtomicLong();

        private SimpleCircuitBreaker(int failureThreshold, Duration openDuration) {
            this.failureThreshold = failureThreshold;
            this.openDurationMillis = openDuration.toMillis();
        }

        void beforeCall() {
            long now = System.currentTimeMillis();
            long openUntil = openUntilEpochMillis.get();
            if (openUntil > now) {
                throw new DeviceStatusQueryException(
                        DeviceStatusQueryException.Reason.CIRCUIT_OPEN,
                        false,
                        "IoT backend circuit breaker is open");
            }
            if (openUntil != 0 && openUntil <= now) {
                openUntilEpochMillis.compareAndSet(openUntil, 0);
                consecutiveFailures.set(0);
            }
        }

        void recordSuccess() {
            consecutiveFailures.set(0);
            openUntilEpochMillis.set(0);
        }

        void recordFailure() {
            int failures = consecutiveFailures.incrementAndGet();
            if (failures >= failureThreshold) {
                openUntilEpochMillis.set(System.currentTimeMillis() + openDurationMillis);
                consecutiveFailures.set(0);
            }
        }
    }
}
