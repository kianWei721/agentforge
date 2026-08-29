package com.agentforge.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentforge.tool-confirmation")
public class ToolConfirmationProperties {

    private Duration ttl = Duration.ofMinutes(5);

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public void validate() {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalStateException("agentforge.tool-confirmation.ttl must be positive");
        }
        if (ttl.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalStateException(
                    "agentforge.tool-confirmation.ttl must not exceed 24 hours");
        }
    }
}
