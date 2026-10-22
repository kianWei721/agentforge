package com.agentforge.rag;

import com.agentforge.core.security.AccessPrincipal;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * The single definition of who may read which knowledge.
 *
 * <p>The Elasticsearch filter, the SQL predicate and {@link KnowledgeAccessGuard} are all
 * translations of {@link #permits}. They are built from {@link #unrestrictedVisibilities} and
 * {@link #customerScope} so the three cannot drift apart silently.
 */
public final class KnowledgeAccessPolicy {

    private static final Set<KnowledgeVisibility> STAFF_VISIBILITIES =
            Collections.unmodifiableSet(EnumSet.allOf(KnowledgeVisibility.class));
    private static final Set<KnowledgeVisibility> CUSTOMER_VISIBILITIES =
            Collections.unmodifiableSet(EnumSet.of(KnowledgeVisibility.PUBLIC));

    private KnowledgeAccessPolicy() {}

    /** Visibilities the principal may read without a customer match, in declaration order. */
    public static Set<KnowledgeVisibility> unrestrictedVisibilities(AccessPrincipal principal) {
        return requirePrincipal(principal).isStaff()
                ? STAFF_VISIBILITIES
                : CUSTOMER_VISIBILITIES;
    }

    /** Customer whose CUSTOMER-visibility documents the principal may additionally read. */
    public static Optional<String> customerScope(AccessPrincipal principal) {
        return requirePrincipal(principal).isStaff()
                ? Optional.empty()
                : Optional.of(principal.customerId());
    }

    public static boolean permits(AccessPrincipal principal, KnowledgeAccessScope scope) {
        if (scope == null) {
            return false;
        }
        if (unrestrictedVisibilities(principal).contains(scope.visibility())) {
            return true;
        }
        return scope.visibility() == KnowledgeVisibility.CUSTOMER
                && customerScope(principal).filter(scope.customerId()::equals).isPresent();
    }

    public static AccessPrincipal requirePrincipal(AccessPrincipal principal) {
        if (principal == null) {
            throw new IllegalArgumentException("principal is required for knowledge access");
        }
        return principal;
    }
}
