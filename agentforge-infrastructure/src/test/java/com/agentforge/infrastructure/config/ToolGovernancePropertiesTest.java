package com.agentforge.infrastructure.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ToolGovernancePropertiesTest {

    @Test
    void shouldAcceptDefaults() {
        ToolGovernanceProperties properties = new ToolGovernanceProperties();
        assertDoesNotThrow(properties::validate);
        assertTrue(properties.isEnabled());
        assertTrue(properties.isBlockRepeatedNonRetryableCalls());
        assertEquals(256, properties.getMaxRecordedInvocations());
    }

    @Test
    void shouldRejectUnboundedInvocationLedger() {
        ToolGovernanceProperties properties = new ToolGovernanceProperties();
        properties.setMaxRecordedInvocations(0);
        assertThrows(IllegalArgumentException.class, properties::validate);

        properties.setMaxRecordedInvocations(4097);
        assertThrows(IllegalArgumentException.class, properties::validate);
    }
}
