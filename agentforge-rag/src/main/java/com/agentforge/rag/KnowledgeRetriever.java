package com.agentforge.rag;

import com.agentforge.core.security.AccessPrincipal;
import java.util.List;

/**
 * Every search runs as a principal. There is deliberately no overload without one, so a caller
 * cannot forget the access boundary.
 */
public interface KnowledgeRetriever {

    List<KnowledgeSearchHit> search(String query, int topK, AccessPrincipal principal);

    default KnowledgeRetrievalResult searchDetailed(
            String query, int topK, AccessPrincipal principal) {
        return KnowledgeRetrievalResult.of(search(query, topK, principal));
    }

    default String mode() {
        return "UNKNOWN";
    }
}
