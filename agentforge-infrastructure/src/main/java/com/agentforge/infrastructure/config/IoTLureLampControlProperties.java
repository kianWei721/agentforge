package com.agentforge.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentforge.iot.lure-lamp-control")
public class IoTLureLampControlProperties {

    private boolean enabled = false;
    private String latestPropertiesPath =
            "/admin-api/device/web/devices/{deviceId}/properties/latest";
    private String commandPath =
            "/admin-api/device/web/devices/command/send";
    private Duration requestTimeout = Duration.ofSeconds(3);
    private int verifyAttempts = 10;
    private Duration verifyInterval = Duration.ofMillis(500);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getLatestPropertiesPath() {
        return latestPropertiesPath;
    }

    public void setLatestPropertiesPath(String latestPropertiesPath) {
        this.latestPropertiesPath =
                latestPropertiesPath == null ? "" : latestPropertiesPath.trim();
    }

    public String getCommandPath() {
        return commandPath;
    }

    public void setCommandPath(String commandPath) {
        this.commandPath = commandPath == null ? "" : commandPath.trim();
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public int getVerifyAttempts() {
        return verifyAttempts;
    }

    public void setVerifyAttempts(int verifyAttempts) {
        this.verifyAttempts = verifyAttempts;
    }

    public Duration getVerifyInterval() {
        return verifyInterval;
    }

    public void setVerifyInterval(Duration verifyInterval) {
        this.verifyInterval = verifyInterval;
    }

    public void validate(IoTDeviceStatusProperties sharedHttpProperties) {
        if (!enabled) {
            return;
        }
        if (sharedHttpProperties == null || !sharedHttpProperties.isHttpMode()) {
            throw new IllegalArgumentException(
                    "lure lamp control requires agentforge.iot.device-status.mode=http");
        }
        sharedHttpProperties.validateForHttp();

        if (latestPropertiesPath.isBlank()
                || !latestPropertiesPath.startsWith("/")
                || !latestPropertiesPath.contains("{deviceId}")) {
            throw new IllegalArgumentException(
                    "agentforge.iot.lure-lamp-control.latest-properties-path must start with / and contain {deviceId}");
        }
        if (commandPath.isBlank() || !commandPath.startsWith("/")) {
            throw new IllegalArgumentException(
                    "agentforge.iot.lure-lamp-control.command-path must start with /");
        }
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "agentforge.iot.lure-lamp-control.request-timeout must be positive");
        }
        if (verifyAttempts < 1 || verifyAttempts > 20) {
            throw new IllegalArgumentException(
                    "agentforge.iot.lure-lamp-control.verify-attempts must be between 1 and 20");
        }
        if (verifyInterval == null || verifyInterval.isNegative()) {
            throw new IllegalArgumentException(
                    "agentforge.iot.lure-lamp-control.verify-interval must not be negative");
        }
    }
}
