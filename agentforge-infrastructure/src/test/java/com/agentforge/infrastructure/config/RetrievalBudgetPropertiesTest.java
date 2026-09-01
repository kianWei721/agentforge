package com.agentforge.infrastructure.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RetrievalBudgetPropertiesTest {

    @Test
    void defaultsShouldEnableThreeCallsPerExecution() {
        RetrievalBudgetProperties properties =
                new RetrievalBudgetProperties();

        assertTrue(properties.isEnabled());
        assertEquals(3, properties.getMaxCallsPerExecution());
        assertDoesNotThrow(properties::validate);
    }

    @Test
    void shouldRejectInvalidLimit() {
        RetrievalBudgetProperties properties =
                new RetrievalBudgetProperties();
        properties.setMaxCallsPerExecution(0);

        assertThrows(
                IllegalArgumentException.class,
                properties::validate);
    }
}
