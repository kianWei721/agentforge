package com.agentforge.infrastructure.config;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.core.security.AccessPrincipal;
import com.agentforge.infrastructure.security.ConfiguredPrincipalDirectory;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PrincipalDirectoryPropertiesTest {

    @Test
    void shouldResolveConfiguredStaffAndCustomers() {
        PrincipalDirectoryProperties properties = new PrincipalDirectoryProperties();
        properties.setStaffUserIds(List.of("dev-staff"));
        properties.setCustomerUsers(List.of("demo-customer-a=cust-a", " demo-customer-b = cust-b "));

        ConfiguredPrincipalDirectory directory =
                new ConfiguredPrincipalDirectory(properties.toPrincipals());

        assertEquals(Optional.of(AccessPrincipal.staff("dev-staff")), directory.resolve("dev-staff"));
        assertEquals(
                Optional.of(AccessPrincipal.customer("demo-customer-b", "cust-b")),
                directory.resolve("demo-customer-b"));
        assertEquals(Optional.empty(), directory.resolve("someone-else"));
        assertEquals(Optional.empty(), directory.resolve(null));
    }

    @Test
    void emptyConfigurationShouldResolveNobody() {
        assertTrue(new PrincipalDirectoryProperties().toPrincipals().isEmpty());
    }

    @Test
    void shouldRejectUserRegisteredAsBothStaffAndCustomer() {
        PrincipalDirectoryProperties properties = new PrincipalDirectoryProperties();
        properties.setStaffUserIds(List.of("user-1"));
        properties.setCustomerUsers(List.of("user-1=cust-a"));

        assertThrows(IllegalArgumentException.class, properties::toPrincipals);
    }

    @Test
    void shouldRejectMalformedCustomerEntries() {
        for (String entry : List.of("user-1", "=cust-a", "user-1=", "user-1= ")) {
            PrincipalDirectoryProperties properties = new PrincipalDirectoryProperties();
            properties.setCustomerUsers(List.of(entry));
            assertThrows(IllegalArgumentException.class, properties::toPrincipals, entry);
        }
    }

    @Test
    void shouldRejectBlankStaffUserId() {
        PrincipalDirectoryProperties properties = new PrincipalDirectoryProperties();
        properties.setStaffUserIds(List.of(" "));

        assertThrows(IllegalArgumentException.class, properties::toPrincipals);
    }
}
