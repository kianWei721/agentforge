package com.agentforge.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentforge.session-cache")
public class AgentSessionCacheProperties {

    private boolean enabled = true;
    private String keyPrefix = "agentforge:session:";
    private Duration ttl = Duration.ofHours(24);
    private int restoreMaxMessages = 40;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public int getRestoreMaxMessages() {
        return restoreMaxMessages;
    }

    public void setRestoreMaxMessages(int restoreMaxMessages) {
        this.restoreMaxMessages = restoreMaxMessages;
    }

    public void validate() {
        if (keyPrefix == null || keyPrefix.isBlank()) {
            throw new IllegalArgumentException("agentforge.session-cache.key-prefix must not be blank");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("agentforge.session-cache.ttl must be positive");
        }
        if (restoreMaxMessages < 1 || restoreMaxMessages > 500) {
            throw new IllegalArgumentException(
                    "agentforge.session-cache.restore-max-messages must be between 1 and 500");
        }
    }
}
