package com.agentforge.rag;

/**
 * @param guardDroppedHits backend hits removed by {@link KnowledgeAccessGuard}. Non-zero means the
 *     index is stale (deleted/disabled documents not yet re-indexed) or the backend filter is wrong.
 */
public record KnowledgeAccessDiagnostics(String principalType, int guardDroppedHits) {}
