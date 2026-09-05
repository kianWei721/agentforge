package com.agentforge.infrastructure.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentforge.iot.device-status")
public class IoTDeviceStatusProperties {

    public enum Mode {
        DEMO,
        HTTP
    }

    private Mode mode = Mode.DEMO;
    private String baseUrl = "";
    private String detailPath = "/api/iot/devices/{deviceId}";
    private String detailsPath = "/api/iot/devices/{deviceId}/details";
    private String eventsHistoryPath = "/api/iot/devices/{deviceId}/events/history";
    private String accessToken = "";
    private String tenantId = "";
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration requestTimeout = Duration.ofSeconds(3);
    private int maxAttempts = 2;
    private Duration retryBackoff = Duration.ofMillis(150);
    private int circuitBreakerFailureThreshold = 3;
    private Duration circuitBreakerOpenDuration = Duration.ofSeconds(30);

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.DEMO : mode;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
    }

    public String getDetailPath() {
        return detailPath;
    }

    public void setDetailPath(String detailPath) {
        this.detailPath = detailPath == null ? "" : detailPath.trim();
    }

    public String getDetailsPath() {
        return detailsPath;
    }

    public void setDetailsPath(String detailsPath) {
        this.detailsPath = detailsPath == null ? "" : detailsPath.trim();
    }

    public String getEventsHistoryPath() {
        return eventsHistoryPath;
    }

    public void setEventsHistoryPath(String eventsHistoryPath) {
        this.eventsHistoryPath = eventsHistoryPath == null ? "" : eventsHistoryPath.trim();
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken == null ? "" : accessToken.trim();
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId == null ? "" : tenantId.trim();
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getRetryBackoff() {
        return retryBackoff;
    }

    public void setRetryBackoff(Duration retryBackoff) {
        this.retryBackoff = retryBackoff;
    }

    public int getCircuitBreakerFailureThreshold() {
        return circuitBreakerFailureThreshold;
    }

    public void setCircuitBreakerFailureThreshold(int circuitBreakerFailureThreshold) {
        this.circuitBreakerFailureThreshold = circuitBreakerFailureThreshold;
    }

    public Duration getCircuitBreakerOpenDuration() {
        return circuitBreakerOpenDuration;
    }

    public void setCircuitBreakerOpenDuration(Duration circuitBreakerOpenDuration) {
        this.circuitBreakerOpenDuration = circuitBreakerOpenDuration;
    }

    public boolean isHttpMode() {
        return mode == Mode.HTTP;
    }

    public boolean isConfigured() {
        if (!isHttpMode()) {
            return true;
        }
        try {
            validateForHttp();
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public void validateForHttp() {
        if (!isHttpMode()) {
            return;
        }

        if (baseUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "agentforge.iot.device-status.base-url must not be blank in HTTP mode");
        }
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "agentforge.iot.device-status.base-url must be a valid URI", exception);
        }
        if (uri.getScheme() == null
                || (!"http".equalsIgnoreCase(uri.getScheme())
                        && !"https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "agentforge.iot.device-status.base-url must be an http/https origin");
        }

        if (detailPath.isBlank()
                || !detailPath.startsWith("/")
                || !detailPath.contains("{deviceId}")) {
            throw new IllegalArgumentException(
                    "agentforge.iot.device-status.detail-path must start with / and contain {deviceId}");
        }
        validateDevicePath(detailsPath, "details-path");
        validateDevicePath(eventsHistoryPath, "events-history-path");
        if (accessToken.isBlank()) {
            throw new IllegalArgumentException(
                    "agentforge.iot.device-status.access-token must not be blank in HTTP mode");
        }
        if (!tenantId.matches("\\d{1,19}")) {
            throw new IllegalArgumentException(
                    "agentforge.iot.device-status.tenant-id must be numeric in HTTP mode");
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "agentforge.iot.device-status.connect-timeout must be positive");
        }
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "agentforge.iot.device-status.request-timeout must be positive");
        }
        if (maxAttempts < 1 || maxAttempts > 3) {
            throw new IllegalArgumentException(
                    "agentforge.iot.device-status.max-attempts must be between 1 and 3");
        }
        if (retryBackoff == null || retryBackoff.isNegative()) {
            throw new IllegalArgumentException(
                    "agentforge.iot.device-status.retry-backoff must not be negative");
        }
        if (circuitBreakerFailureThreshold < 1) {
            throw new IllegalArgumentException(
                    "agentforge.iot.device-status.circuit-breaker-failure-threshold must be positive");
        }
        if (circuitBreakerOpenDuration == null
                || circuitBreakerOpenDuration.isZero()
                || circuitBreakerOpenDuration.isNegative()) {
            throw new IllegalArgumentException(
                    "agentforge.iot.device-status.circuit-breaker-open-duration must be positive");
        }
    }

    private static void validateDevicePath(String path, String name) {
        if (path == null
                || path.isBlank()
                || !path.startsWith("/")
                || !path.contains("{deviceId}")) {
            throw new IllegalArgumentException(
                    "agentforge.iot.device-status." + name
                            + " must start with / and contain {deviceId}");
        }
    }

    public String authorizationHeaderValue() {
        String token = accessToken.trim();
        return token.regionMatches(true, 0, "Bearer ", 0, 7) ? token : "Bearer " + token;
    }
}
