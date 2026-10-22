package com.agentforge.app.api;

import com.agentforge.rag.KnowledgeAccessDiagnostics;
import com.agentforge.rag.KnowledgeAnswerabilityDiagnostics;
import com.agentforge.rag.KnowledgeQueryRewriteDiagnostics;
import com.agentforge.rag.KnowledgeRerankDiagnostics;
import com.agentforge.rag.KnowledgeSearchHit;
import java.util.List;

public record KnowledgeSearchResponse(
        String retrievalMode,
        int count,
        boolean noAnswer,
        String noAnswerReason,
        List<KnowledgeSearchHit> hits,
        KnowledgeQueryRewriteDiagnostics queryRewriteDiagnostics,
        KnowledgeRerankDiagnostics rerankDiagnostics,
        KnowledgeAnswerabilityDiagnostics answerabilityDiagnostics,
        KnowledgeAccessDiagnostics accessDiagnostics) {}
