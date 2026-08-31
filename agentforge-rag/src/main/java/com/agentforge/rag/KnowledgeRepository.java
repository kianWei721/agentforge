package com.agentforge.rag;

import java.util.List;
import java.util.Optional;

public interface KnowledgeRepository {

    KnowledgeDocument save(KnowledgeDocument document, List<KnowledgeChunk> chunks);

    Optional<KnowledgeDocument> findById(String id);

    List<KnowledgeDocument> findAll();

    List<KnowledgeCandidate> findEnabledCandidates(int maxCandidates);

    void deleteById(String id);
}
