package com.agentforge.app.api;

import com.agentforge.rag.KnowledgeSearchHit;
import java.util.List;

public record KnowledgeSearchResponse(
        String retrievalMode,
        int count,
        List<KnowledgeSearchHit> hits) {}
