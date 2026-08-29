package com.agentforge.infrastructure.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ToolPolicyPropertiesTest {

    @Test
    void defaultsShouldFailClosedForWriteAndHighRiskReadOnlyTools() {
        ToolPolicyProperties properties = new ToolPolicyProperties();

        assertTrue(properties.isEnabled());
        assertTrue(properties.isRequireConfirmationForWriteTools());
        assertTrue(properties.isRequireConfirmationForHighRiskReadOnly());
    }
}
