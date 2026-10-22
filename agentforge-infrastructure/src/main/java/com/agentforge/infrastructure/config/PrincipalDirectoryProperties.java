package com.agentforge.infrastructure.config;

import com.agentforge.core.security.AccessPrincipal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration-backed principal registry for the demo deployment. A real deployment would read
 * the same mapping from the identity provider or an HR / CRM system.
 */
@ConfigurationProperties(prefix = "agentforge.security.principals")
public class PrincipalDirectoryProperties {

    private List<String> staffUserIds = new ArrayList<>();
    /** Entries in {@code userId=customerId} form. */
    private List<String> customerUsers = new ArrayList<>();

    public List<String> getStaffUserIds() {
        return staffUserIds;
    }

    public void setStaffUserIds(List<String> staffUserIds) {
        this.staffUserIds = staffUserIds == null ? new ArrayList<>() : staffUserIds;
    }

    public List<String> getCustomerUsers() {
        return customerUsers;
    }

    public void setCustomerUsers(List<String> customerUsers) {
        this.customerUsers = customerUsers == null ? new ArrayList<>() : customerUsers;
    }

    /**
     * Validates and builds the user id to principal map. Fails startup on malformed entries, a user
     * registered twice, or a user that is both staff and customer: an ambiguous identity must never
     * silently resolve to the more privileged side.
     */
    public Map<String, AccessPrincipal> toPrincipals() {
        Map<String, AccessPrincipal> principals = new LinkedHashMap<>();
        for (String raw : staffUserIds) {
            register(principals, AccessPrincipal.staff(requireUserId(raw, "staff-user-ids")));
        }
        for (String raw : customerUsers) {
            String entry = raw == null ? "" : raw.trim();
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                throw new IllegalArgumentException(
                        "agentforge.security.principals.customer-users entries must be"
                                + " userId=customerId, got: '" + entry + "'");
            }
            String userId = requireUserId(entry.substring(0, separator), "customer-users");
            String customerId = entry.substring(separator + 1).trim();
            register(principals, AccessPrincipal.customer(userId, customerId));
        }
        return Map.copyOf(principals);
    }

    private static void register(
            Map<String, AccessPrincipal> principals, AccessPrincipal principal) {
        AccessPrincipal previous = principals.putIfAbsent(principal.userId(), principal);
        if (previous != null) {
            throw new IllegalArgumentException(
                    "agentforge.security.principals registers user '"
                            + principal.userId()
                            + "' more than once ("
                            + previous.type()
                            + " and "
                            + principal.type()
                            + ")");
        }
    }

    private static String requireUserId(String value, String property) {
        String userId = value == null ? "" : value.trim();
        if (userId.isEmpty()) {
            throw new IllegalArgumentException(
                    "agentforge.security.principals." + property + " contains a blank user id");
        }
        return userId;
    }
}
