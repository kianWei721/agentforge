package com.agentforge.core.security;

/**
 * Server-resolved caller identity.
 *
 * <p>Production code obtains it only from {@link PrincipalDirectory}. It is never built from request
 * bodies, query parameters, or model tool arguments, so a caller cannot claim to be staff or another
 * customer.
 */
public record AccessPrincipal(String userId, PrincipalType type, String customerId) {

    public AccessPrincipal {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("principal type is required");
        }
        userId = userId.trim();
        customerId = customerId == null || customerId.isBlank() ? null : customerId.trim();
        if (type == PrincipalType.CUSTOMER && customerId == null) {
            throw new IllegalArgumentException("customer principal requires customerId");
        }
        if (type == PrincipalType.STAFF && customerId != null) {
            throw new IllegalArgumentException("staff principal must not carry customerId");
        }
    }

    public static AccessPrincipal staff(String userId) {
        return new AccessPrincipal(userId, PrincipalType.STAFF, null);
    }

    public static AccessPrincipal customer(String userId, String customerId) {
        return new AccessPrincipal(userId, PrincipalType.CUSTOMER, customerId);
    }

    public boolean isStaff() {
        return type == PrincipalType.STAFF;
    }
}
