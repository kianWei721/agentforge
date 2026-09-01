package com.agentforge.tool.knowledge;

import com.agentforge.rag.KnowledgeRetriever;
import com.agentforge.rag.KnowledgeSearchHit;
import com.agentforge.tool.AgentTool;
import com.agentforge.tool.ToolInvocationContext;
import com.agentforge.tool.ToolResult;
import com.agentforge.tool.ToolRiskLevel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class KnowledgeSearchTool implements AgentTool {

    public static final String NAME = "search_knowledge";
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 8;

    private final KnowledgeRetriever retriever;

    public KnowledgeSearchTool(KnowledgeRetriever retriever) {
        this.retriever = retriever;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "检索 AgentForge 静态知识库中的设备说明书、故障手册、SOP 和平台文档。"
                + "返回的是知识依据，不是设备当前状态。用于解释含义、查找排查流程和建议步骤。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> topK = new LinkedHashMap<>();
        topK.put("type", "integer");
        topK.put("minimum", 1);
        topK.put("maximum", MAX_TOP_K);
        topK.put("default", DEFAULT_TOP_K);
        topK.put("description", "最多返回的知识片段数量");

        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "query",
                                Map.of(
                                        "type", "string",
                                        "description", "面向知识库的明确检索问题或关键词"),
                                "topK",
                                topK),
                "required", List.of("query"),
                "additionalProperties", false);
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.LOW;
    }

    @Override
    public ToolResult execute(
            ToolInvocationContext context, Map<String, Object> arguments) {
        String query = text(arguments.get("query"));
        if (query == null) {
            return ToolResult.failure("query is required", "INVALID_ARGUMENT", false);
        }
        Integer topK = parseTopK(arguments.get("topK"));
        if (topK == null) {
            return ToolResult.failure(
                    "topK must be an integer between 1 and 8",
                    "INVALID_ARGUMENT",
                    false);
        }

        try {
            List<KnowledgeSearchHit> hits = retriever.search(query, topK);
            List<Map<String, Object>> resultHits = new ArrayList<>();
            int rank = 1;
            for (KnowledgeSearchHit hit : hits) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("rank", rank++);
                item.put("citationId", hit.citationId());
                item.put("documentId", hit.documentId());
                item.put("chunkId", hit.chunkId());
                item.put("chunkIndex", hit.chunkIndex());
                item.put("title", hit.title());
                item.put("sourceType", hit.sourceType().name());
                if (hit.sourceRef() != null && !hit.sourceRef().isBlank()) {
                    item.put("sourceRef", hit.sourceRef());
                }
                item.put("content", hit.content());
                item.put("score", hit.score());
                item.put("matchType", hit.matchType());
                if (hit.vectorSimilarity() != null) {
                    item.put("vectorSimilarity", hit.vectorSimilarity());
                }
                if (hit.rerankScore() != null) {
                    item.put("rerankScore", hit.rerankScore());
                }
                resultHits.add(item);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("query", query);
            data.put("retrievalMode", retriever.mode());
            data.put("count", resultHits.size());
            data.put("hits", resultHits);
            boolean noAnswer = resultHits.isEmpty();
            data.put("noAnswer", noAnswer);
            if (noAnswer) {
                data.put("noAnswerReason", "NO_RELEVANT_KNOWLEDGE_RETURNED");
            }
            data.put("groundingSemantics", "STATIC_KNOWLEDGE_NOT_RUNTIME_FACT");
            data.put(
                    "relevanceSemantics",
                    "RETURNED_HITS_PASSED_RETRIEVER_RELEVANCE_POLICY");
            return ToolResult.success("knowledge searched", data);
        } catch (RuntimeException exception) {
            return ToolResult.failure(
                    "knowledge search unavailable",
                    "KNOWLEDGE_SEARCH_FAILED",
                    false);
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isBlank() ? null : text;
    }

    private static Integer parseTopK(Object raw) {
        if (raw == null) {
            return DEFAULT_TOP_K;
        }
        try {
            int value;
            if (raw instanceof Number number) {
                double numeric = number.doubleValue();
                if (numeric != Math.rint(numeric)) {
                    return null;
                }
                value = number.intValue();
            } else {
                String text = raw.toString().trim();
                if (!text.matches("\\d+")) {
                    return null;
                }
                value = Integer.parseInt(text);
            }
            return value >= 1 && value <= MAX_TOP_K ? value : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
