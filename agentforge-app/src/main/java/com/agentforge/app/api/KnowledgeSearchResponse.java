package com.agentforge.app.api;

import com.agentforge.rag.KnowledgeRerankDiagnostics;
import com.agentforge.rag.KnowledgeSearchHit;
import java.util.List;

public record KnowledgeSearchResponse(
        String retrievalMode,
        int count,
        boolean noAnswer,
        String noAnswerReason,
        List<KnowledgeSearchHit> hits,
        KnowledgeRerankDiagnostics rerankDiagnostics) {}
