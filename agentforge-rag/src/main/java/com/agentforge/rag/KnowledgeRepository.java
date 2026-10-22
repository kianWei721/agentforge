package com.agentforge.rag;

import com.agentforge.core.security.AccessPrincipal;
import java.util.List;
import java.util.Optional;

public interface KnowledgeRepository extends KnowledgeAccessScopeLookup {

    KnowledgeDocument save(KnowledgeDocument document, List<KnowledgeChunk> chunks);

    Optional<KnowledgeDocument> findById(String id);

    List<KnowledgeDocument> findAll();

    /** All enabled chunks regardless of audience. For index rebuilds only, never for serving. */
    List<KnowledgeCandidate> findEnabledCandidates(int maxCandidates);

    /**
     * Enabled chunks the principal may read. The access predicate must be applied before the limit,
     * otherwise unreadable chunks would crowd readable ones out of the window.
     */
    List<KnowledgeCandidate> findAccessibleCandidates(int maxCandidates, AccessPrincipal principal);

    void deleteById(String id);
}
