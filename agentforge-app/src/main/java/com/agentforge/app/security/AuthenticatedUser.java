package com.agentforge.app.security;

import java.util.List;
import java.util.Objects;

/**
 * The caller's identity, resolved from the access token.
 *
 * <p>This is the <em>only</em> supported source of {@code userId} in the web layer.
 * Request bodies and query parameters must never carry it: before authentication
 * existed, {@code userId} was self-reported, which made every ownership check —
 * including tool-confirmation approval — bypassable by sending someone else's id.
 */
public record AuthenticatedUser(String userId, List<String> roles) {

    /** Matches the ceiling {@code ConversationService} enforces on user ids. */
    public static final int MAX_USER_ID_LENGTH = 128;

    public AuthenticatedUser {
        Objects.requireNonNull(userId, "userId must not be null");
        if (userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (userId.length() > MAX_USER_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "userId must not exceed " + MAX_USER_ID_LENGTH + " characters");
        }
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
