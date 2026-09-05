package com.agentforge.infrastructure.tool;

import com.agentforge.infrastructure.config.IoTDeviceStatusProperties;
import com.agentforge.tool.device.DeviceDiagnosticQueryException;
import com.agentforge.tool.telemetry.OutboundHttpTelemetry;
import com.agentforge.tool.telemetry.ToolTelemetrySink;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class ExternalIoTReadHttpSupport {
    private static final String DOWNSTREAM_SYSTEM = "external-iot";
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final IoTDeviceStatusProperties properties;
    private final SimpleCircuitBreaker circuitBreaker;

    ExternalIoTReadHttpSupport(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            IoTDeviceStatusProperties properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        properties.validateForHttp();
        this.circuitBreaker = new SimpleCircuitBreaker(
                properties.getCircuitBreakerFailureThreshold(),
                properties.getCircuitBreakerOpenDuration());
    }

    Optional<JsonNode> getData(String path, ToolTelemetrySink telemetry) {
        ToolTelemetrySink sink = telemetry == null ? ToolTelemetrySink.noop() : telemetry;
        HttpObservation observation = new HttpObservation();
        long started = System.nanoTime();
        try {
            circuitBreaker.beforeCall();
            DeviceDiagnosticQueryException last = null;
            for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
                observation.attempts = attempt;
                observation.httpStatus = null;
                observation.businessCode = null;
                try {
                    Optional<JsonNode> result = executeOnce(path, observation);
                    circuitBreaker.recordSuccess();
                    observation.outcome = result.isPresent() ? "SUCCESS" : "NOT_FOUND";
                    return result;
                } catch (DeviceDiagnosticQueryException exception) {
                    last = exception;
                    observation.outcome = exception.reason().name();
                    if (!exception.retryable()) {
                        circuitBreaker.recordSuccess();
                        throw exception;
                    }
                    if (attempt < properties.getMaxAttempts()) sleepBeforeRetry();
                }
            }
            circuitBreaker.recordFailure();
            throw last == null
                    ? new DeviceDiagnosticQueryException(
                            DeviceDiagnosticQueryException.Reason.BACKEND_UNAVAILABLE,
                            true, "IoT backend request failed")
                    : last;
        } finally {
            sink.recordOutboundHttp(new OutboundHttpTelemetry(
                    "HTTP", DOWNSTREAM_SYSTEM, observation.attempts,
                    observation.httpStatus, observation.businessCode,
                    elapsedMillis(started), observation.outcome));
        }
    }

    Object toJavaValue(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (value.isIntegralNumber()) return value.longValue();
        if (value.isFloatingPointNumber()) return value.doubleValue();
        if (value.isBoolean()) return value.booleanValue();
        if (value.isTextual()) {
            String text = value.asText();
            String trimmed = text == null ? "" : text.trim();
            if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
                    || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
                try {
                    return normalizeJsonValue(objectMapper.readTree(trimmed));
                } catch (Exception ignored) {
                }
            }
            return text;
        }
        return normalizeJsonValue(value);
    }

    private Object normalizeJsonValue(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (value.isIntegralNumber()) return value.longValue();
        if (value.isFloatingPointNumber()) return value.doubleValue();
        if (value.isBoolean()) return value.booleanValue();
        if (value.isTextual()) return value.asText();
        if (value.isArray()) {
            java.util.List<Object> result = new java.util.ArrayList<>();
            value.forEach(item -> result.add(normalizeJsonValue(item)));
            return result;
        }
        if (value.isObject()) {
            java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
            value.fields().forEachRemaining(
                    entry -> result.put(entry.getKey(), normalizeJsonValue(entry.getValue())));
            return result;
        }
        return objectMapper.convertValue(value, new TypeReference<Object>() {});
    }

    static String normalizeTime(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (value.isIntegralNumber()) return Instant.ofEpochMilli(value.longValue()).toString();
        String text = value.asText();
        return text == null || text.isBlank() ? null : text.trim();
    }

    static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Optional<JsonNode> executeOnce(String path, HttpObservation observation) {
        HttpRequest request = HttpRequest.newBuilder(buildUri(path))
                .timeout(properties.getRequestTimeout())
                .header("Accept", "application/json")
                .header("Authorization", properties.authorizationHeaderValue())
                .header("tenant-id", properties.getTenantId())
                .header("User-Agent", "AgentForge/0.5")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            observation.httpStatus = response.statusCode();
            return parseResponse(response.statusCode(), response.body(), observation);
        } catch (HttpTimeoutException exception) {
            throw failure(DeviceDiagnosticQueryException.Reason.TIMEOUT, true, exception);
        } catch (IOException exception) {
            throw failure(DeviceDiagnosticQueryException.Reason.BACKEND_UNAVAILABLE, true, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(DeviceDiagnosticQueryException.Reason.INTERRUPTED, false, exception);
        }
    }

    private Optional<JsonNode> parseResponse(
            int httpStatus, String body, HttpObservation observation) {
        if (httpStatus == 401) throw failure(DeviceDiagnosticQueryException.Reason.AUTHENTICATION_FAILED, false, httpStatus);
        if (httpStatus == 403) throw failure(DeviceDiagnosticQueryException.Reason.FORBIDDEN, false, httpStatus);
        if (httpStatus == 404) return Optional.empty();
        if (httpStatus >= 500) throw failure(DeviceDiagnosticQueryException.Reason.BACKEND_UNAVAILABLE, true, httpStatus);
        if (httpStatus < 200 || httpStatus >= 300) throw failure(DeviceDiagnosticQueryException.Reason.BAD_REQUEST, false, httpStatus);

        final JsonNode root;
        try {
            root = objectMapper.readTree(body == null ? "" : body);
        } catch (Exception exception) {
            throw failure(DeviceDiagnosticQueryException.Reason.INVALID_RESPONSE, false, exception);
        }
        int code = root.path("code").asInt(-1);
        observation.businessCode = code;
        if (code == 401) throw failure(DeviceDiagnosticQueryException.Reason.AUTHENTICATION_FAILED, false, code);
        if (code == 403) throw failure(DeviceDiagnosticQueryException.Reason.FORBIDDEN, false, code);
        if (code == 404) return Optional.empty();
        if (code == 500 || code == 503) throw failure(DeviceDiagnosticQueryException.Reason.BACKEND_UNAVAILABLE, true, code);
        if (code != 200) throw failure(DeviceDiagnosticQueryException.Reason.BAD_REQUEST, false, code);
        JsonNode data = root.get("data");
        return data == null || data.isNull() ? Optional.empty() : Optional.of(data);
    }

    private URI buildUri(String path) {
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            throw new DeviceDiagnosticQueryException(
                    DeviceDiagnosticQueryException.Reason.BAD_REQUEST, false,
                    "IoT backend path must be an application path");
        }
        String base = properties.getBaseUrl();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        try {
            return URI.create(base + path);
        } catch (IllegalArgumentException exception) {
            throw failure(DeviceDiagnosticQueryException.Reason.BAD_REQUEST, false, exception);
        }
    }

    private void sleepBeforeRetry() {
        try {
            long millis = properties.getRetryBackoff().toMillis();
            if (millis > 0) Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(DeviceDiagnosticQueryException.Reason.INTERRUPTED, false, exception);
        }
    }

    private static DeviceDiagnosticQueryException failure(
            DeviceDiagnosticQueryException.Reason reason, boolean retryable, int code) {
        return new DeviceDiagnosticQueryException(
                reason, retryable, "IoT backend rejected request with code " + code);
    }

    private static DeviceDiagnosticQueryException failure(
            DeviceDiagnosticQueryException.Reason reason, boolean retryable, Throwable cause) {
        return new DeviceDiagnosticQueryException(
                reason, retryable, "IoT backend request failed", cause);
    }

    private static long elapsedMillis(long started) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    private static final class HttpObservation {
        int attempts;
        Integer httpStatus;
        Integer businessCode;
        String outcome = "UNKNOWN";
    }

    private static final class SimpleCircuitBreaker {
        private final int threshold;
        private final long openMillis;
        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicLong openUntil = new AtomicLong();

        private SimpleCircuitBreaker(int threshold, Duration duration) {
            this.threshold = threshold;
            this.openMillis = duration.toMillis();
        }

        void beforeCall() {
            long now = System.currentTimeMillis();
            long until = openUntil.get();
            if (until > now) {
                throw new DeviceDiagnosticQueryException(
                        DeviceDiagnosticQueryException.Reason.CIRCUIT_OPEN,
                        false, "IoT backend circuit breaker is open");
            }
            if (until != 0 && until <= now) {
                openUntil.compareAndSet(until, 0);
                failures.set(0);
            }
        }

        void recordSuccess() {
            failures.set(0);
            openUntil.set(0);
        }

        void recordFailure() {
            if (failures.incrementAndGet() >= threshold) {
                openUntil.set(System.currentTimeMillis() + openMillis);
                failures.set(0);
            }
        }
    }
}
