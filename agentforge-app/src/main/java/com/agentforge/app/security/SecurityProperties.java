package com.agentforge.app.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Authentication configuration.
 *
 * <p>Exactly one of {@code issuer-uri} (an external identity provider) or
 * {@code shared-secret} (a symmetric key, for local development and self-hosted
 * deployments without an IdP) must be set. Supplying neither, or both, fails
 * startup rather than defaulting to something permissive — an unauthenticated
 * runtime is the failure this configuration exists to prevent.
 */
@ConfigurationProperties(prefix = "agentforge.security")
public class SecurityProperties {

    /** Minimum HMAC key length for HS256, per RFC 7518 section 3.2. */
    public static final int MIN_SHARED_SECRET_BYTES = 32;

    private final Jwt jwt = new Jwt();

    public Jwt getJwt() {
        return jwt;
    }

    public void validate() {
        jwt.validate();
    }

    public static class Jwt {

        private String issuerUri = "";
        private String sharedSecret = "";
        private String audience = "";
        private String userIdClaim = "sub";
        private String rolesClaim = "roles";

        public String getIssuerUri() {
            return issuerUri;
        }

        public void setIssuerUri(String issuerUri) {
            this.issuerUri = issuerUri == null ? "" : issuerUri.trim();
        }

        public String getSharedSecret() {
            return sharedSecret;
        }

        public void setSharedSecret(String sharedSecret) {
            this.sharedSecret = sharedSecret == null ? "" : sharedSecret.trim();
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience == null ? "" : audience.trim();
        }

        public String getUserIdClaim() {
            return userIdClaim;
        }

        public void setUserIdClaim(String userIdClaim) {
            this.userIdClaim =
                    userIdClaim == null || userIdClaim.isBlank() ? "sub" : userIdClaim.trim();
        }

        public String getRolesClaim() {
            return rolesClaim;
        }

        public void setRolesClaim(String rolesClaim) {
            this.rolesClaim =
                    rolesClaim == null || rolesClaim.isBlank() ? "roles" : rolesClaim.trim();
        }

        public boolean usesIssuer() {
            return !issuerUri.isEmpty();
        }

        public boolean usesSharedSecret() {
            return !sharedSecret.isEmpty();
        }

        public void validate() {
            if (usesIssuer() == usesSharedSecret()) {
                throw new IllegalStateException(
                        "Exactly one of agentforge.security.jwt.issuer-uri or "
                                + "agentforge.security.jwt.shared-secret must be configured. "
                                + "Refusing to start: the application would otherwise accept "
                                + "unauthenticated or unverifiable requests.");
            }
            if (usesSharedSecret()
                    && sharedSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                            < MIN_SHARED_SECRET_BYTES) {
                throw new IllegalStateException(
                        "agentforge.security.jwt.shared-secret must be at least "
                                + MIN_SHARED_SECRET_BYTES
                                + " bytes for HS256.");
            }
        }
    }
}
