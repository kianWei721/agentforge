package com.agentforge.rag;

import com.agentforge.core.security.AccessPrincipal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Re-checks search backend hits against the document table, the source of truth for ACLs.
 *
 * <p>The search index is derived data. It lags behind deletes, disables and ACL changes until the
 * next rebuild, and its filter is a translation that could be wrong. The backend filter keeps
 * top-k honest; this guard makes sure nothing unreadable survives. It runs before fusion so
 * rerank and answerability models, which are external LLM calls, never see such a chunk.
 */
public final class KnowledgeAccessGuard {

    private final KnowledgeAccessScopeLookup lookup;

    public KnowledgeAccessGuard(KnowledgeAccessScopeLookup lookup) {
        if (lookup == null) {
            throw new IllegalArgumentException("lookup is required");
        }
        this.lookup = lookup;
    }

    public Result retainReadable(
            AccessPrincipal principal,
            List<KnowledgeBackendHit> lexical,
            List<KnowledgeBackendHit> vector) {
        KnowledgeAccessPolicy.requirePrincipal(principal);
        List<KnowledgeBackendHit> lexicalHits = lexical == null ? List.of() : lexical;
        List<KnowledgeBackendHit> vectorHits = vector == null ? List.of() : vector;

        Set<String> documentIds = new LinkedHashSet<>();
        lexicalHits.forEach(hit -> documentIds.add(hit.documentId()));
        vectorHits.forEach(hit -> documentIds.add(hit.documentId()));
        Map<String, KnowledgeAccessScope> scopes =
                documentIds.isEmpty() ? Map.of() : lookup.findReadableScopes(documentIds);

        List<KnowledgeBackendHit> keptLexical = retain(lexicalHits, scopes, principal);
        List<KnowledgeBackendHit> keptVector = retain(vectorHits, scopes, principal);
        int dropped =
                (lexicalHits.size() - keptLexical.size())
                        + (vectorHits.size() - keptVector.size());
        return new Result(keptLexical, keptVector, dropped);
    }

    private static List<KnowledgeBackendHit> retain(
            List<KnowledgeBackendHit> hits,
            Map<String, KnowledgeAccessScope> scopes,
            AccessPrincipal principal) {
        return hits.stream()
                .filter(
                        hit ->
                                KnowledgeAccessPolicy.permits(
                                        principal, scopes.get(hit.documentId())))
                .toList();
    }

    public record Result(
            List<KnowledgeBackendHit> lexical,
            List<KnowledgeBackendHit> vector,
            int droppedHits) {}
}
