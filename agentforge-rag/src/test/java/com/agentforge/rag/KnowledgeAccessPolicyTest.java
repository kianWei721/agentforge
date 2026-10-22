package com.agentforge.rag;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.core.security.AccessPrincipal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KnowledgeAccessPolicyTest {

    private static final AccessPrincipal STAFF = AccessPrincipal.staff("accountant-1");
    private static final AccessPrincipal CUSTOMER_A = AccessPrincipal.customer("user-a", "cust-a");
    private static final AccessPrincipal CUSTOMER_B = AccessPrincipal.customer("user-b", "cust-b");

    private static final KnowledgeAccessScope INTERNAL = KnowledgeAccessScope.internal();
    private static final KnowledgeAccessScope PUBLIC = KnowledgeAccessScope.publicAudience();
    private static final KnowledgeAccessScope OWNED_BY_A = KnowledgeAccessScope.customer("cust-a");
    private static final KnowledgeAccessScope OWNED_BY_B = KnowledgeAccessScope.customer("cust-b");

    @Test
    void staffShouldReadEverything() {
        for (KnowledgeAccessScope scope : List.of(INTERNAL, PUBLIC, OWNED_BY_A, OWNED_BY_B)) {
            assertTrue(KnowledgeAccessPolicy.permits(STAFF, scope), scope.toString());
        }
    }

    @Test
    void customerShouldReadPublicAndOwnOnly() {
        assertFalse(KnowledgeAccessPolicy.permits(CUSTOMER_A, INTERNAL));
        assertTrue(KnowledgeAccessPolicy.permits(CUSTOMER_A, PUBLIC));
        assertTrue(KnowledgeAccessPolicy.permits(CUSTOMER_A, OWNED_BY_A));
        assertFalse(KnowledgeAccessPolicy.permits(CUSTOMER_A, OWNED_BY_B));

        assertFalse(KnowledgeAccessPolicy.permits(CUSTOMER_B, OWNED_BY_A));
        assertTrue(KnowledgeAccessPolicy.permits(CUSTOMER_B, OWNED_BY_B));
    }

    @Test
    void unknownScopeShouldBeUnreadable() {
        // A document missing from the ACL lookup (deleted, disabled) has no scope.
        assertFalse(KnowledgeAccessPolicy.permits(STAFF, null));
        assertFalse(KnowledgeAccessPolicy.permits(CUSTOMER_A, null));
    }

    @Test
    void filterBuildingBlocksShouldMatchPermits() {
        assertEquals(
                Set.of(KnowledgeVisibility.INTERNAL, KnowledgeVisibility.PUBLIC, KnowledgeVisibility.CUSTOMER),
                KnowledgeAccessPolicy.unrestrictedVisibilities(STAFF));
        assertTrue(KnowledgeAccessPolicy.customerScope(STAFF).isEmpty());

        assertEquals(
                Set.of(KnowledgeVisibility.PUBLIC),
                KnowledgeAccessPolicy.unrestrictedVisibilities(CUSTOMER_A));
        assertEquals("cust-a", KnowledgeAccessPolicy.customerScope(CUSTOMER_A).orElseThrow());
    }

    @Test
    void shouldRejectMissingPrincipal() {
        assertThrows(IllegalArgumentException.class, () -> KnowledgeAccessPolicy.permits(null, PUBLIC));
    }

    @Test
    void scopeShouldRejectInconsistentCustomer() {
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeAccessScope(KnowledgeVisibility.CUSTOMER, " "));
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeAccessScope(KnowledgeVisibility.PUBLIC, "cust-a"));
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeAccessScope(null, null));
    }

    @Test
    void principalShouldRejectInconsistentIdentity() {
        assertThrows(IllegalArgumentException.class, () -> AccessPrincipal.customer("user-a", null));
        assertThrows(IllegalArgumentException.class, () -> AccessPrincipal.staff(" "));
    }
}
