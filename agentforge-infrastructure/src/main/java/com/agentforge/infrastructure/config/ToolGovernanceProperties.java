package com.agentforge.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentforge.tool-governance")
public class ToolGovernanceProperties {

    private boolean enabled = true;
    private boolean blockRepeatedNonRetryableCalls = true;
    private int maxRecordedInvocations = 256;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isBlockRepeatedNonRetryableCalls() {
        return blockRepeatedNonRetryableCalls;
    }

    public void setBlockRepeatedNonRetryableCalls(boolean blockRepeatedNonRetryableCalls) {
        this.blockRepeatedNonRetryableCalls = blockRepeatedNonRetryableCalls;
    }

    public int getMaxRecordedInvocations() {
        return maxRecordedInvocations;
    }

    public void setMaxRecordedInvocations(int maxRecordedInvocations) {
        this.maxRecordedInvocations = maxRecordedInvocations;
    }

    public void validate() {
        if (maxRecordedInvocations < 1 || maxRecordedInvocations > 4096) {
            throw new IllegalArgumentException(
                    "agentforge.tool-governance.max-recorded-invocations must be between 1 and 4096");
        }
    }
}
