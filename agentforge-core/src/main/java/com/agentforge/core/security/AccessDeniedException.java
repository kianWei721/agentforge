package com.agentforge.core.security;

public class AccessDeniedException extends RuntimeException {

    public AccessDeniedException(String message) {
        super(message);
    }

    public static void requireStaff(AccessPrincipal principal, String action) {
        if (principal == null || !principal.isStaff()) {
            throw new AccessDeniedException(action + " requires a staff principal");
        }
    }
}
