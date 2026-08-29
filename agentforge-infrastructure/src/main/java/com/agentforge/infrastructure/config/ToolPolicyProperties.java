package com.agentforge.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentforge.tool-policy")
public class ToolPolicyProperties {

    private boolean enabled = true;
    private boolean requireConfirmationForWriteTools = true;
    private boolean requireConfirmationForHighRiskReadOnly = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isRequireConfirmationForWriteTools() {
        return requireConfirmationForWriteTools;
    }

    public void setRequireConfirmationForWriteTools(boolean value) {
        this.requireConfirmationForWriteTools = value;
    }

    public boolean isRequireConfirmationForHighRiskReadOnly() {
        return requireConfirmationForHighRiskReadOnly;
    }

    public void setRequireConfirmationForHighRiskReadOnly(boolean value) {
        this.requireConfirmationForHighRiskReadOnly = value;
    }
}
