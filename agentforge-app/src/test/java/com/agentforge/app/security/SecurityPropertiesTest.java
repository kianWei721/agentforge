package com.agentforge.app.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SecurityPropertiesTest {

    private static final String VALID_SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void refusesStartupWhenNoVerificationSourceIsConfigured() {
        SecurityProperties properties = new SecurityProperties();

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, properties::validate);

        assertTrue(exception.getMessage().contains("Exactly one of"));
    }

    @Test
    void refusesStartupWhenBothVerificationSourcesAreConfigured() {
        SecurityProperties properties = new SecurityProperties();
        properties.getJwt().setIssuerUri("https://idp.example.com");
        properties.getJwt().setSharedSecret(VALID_SECRET);

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void refusesSharedSecretShorterThanHs256Requires() {
        SecurityProperties properties = new SecurityProperties();
        properties.getJwt().setSharedSecret("too-short");

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, properties::validate);

        assertTrue(exception.getMessage().contains("at least"));
    }

    @Test
    void acceptsEitherVerificationSourceOnItsOwn() {
        SecurityProperties issuer = new SecurityProperties();
        issuer.getJwt().setIssuerUri("https://idp.example.com");
        assertDoesNotThrow(issuer::validate);

        SecurityProperties secret = new SecurityProperties();
        secret.getJwt().setSharedSecret(VALID_SECRET);
        assertDoesNotThrow(secret::validate);
    }

    @Test
    void blankClaimNamesFallBackToDefaults() {
        SecurityProperties properties = new SecurityProperties();
        properties.getJwt().setUserIdClaim("  ");
        properties.getJwt().setRolesClaim(null);

        assertTrue("sub".equals(properties.getJwt().getUserIdClaim()));
        assertTrue("roles".equals(properties.getJwt().getRolesClaim()));
    }
}
