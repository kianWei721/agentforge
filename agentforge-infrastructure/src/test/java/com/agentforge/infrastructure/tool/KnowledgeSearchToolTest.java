package com.agentforge.infrastructure.tool;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.core.model.ModelCallFailedException;
import com.agentforge.core.security.AccessPrincipal;
import com.agentforge.infrastructure.security.ConfiguredPrincipalDirectory;
import com.agentforge.rag.KnowledgeModelCall;
import com.agentforge.rag.KnowledgeModelCallDiagnostics;
import com.agentforge.rag.KnowledgeRetrievalResult;
import com.agentforge.rag.KnowledgeRetriever;
import com.agentforge.rag.KnowledgeSearchHit;
import com.agentforge.tool.ToolInvocationContext;
import com.agentforge.tool.ToolResult;
import com.agentforge.tool.knowledge.KnowledgeSearchTool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KnowledgeSearchToolTest {

    private final List<AccessPrincipal> searchedAs = new ArrayList<>();
    private final KnowledgeRetriever retriever =
            new KnowledgeRetriever() {
                @Override
                public List<KnowledgeSearchHit> search(
                        String query, int topK, AccessPrincipal principal) {
                    searchedAs.add(principal);
                    return List.of();
                }
            };
    private final KnowledgeSearchTool tool =
            new KnowledgeSearchTool(
                    retriever,
                    new ConfiguredPrincipalDirectory(
                            Map.of("user-a", AccessPrincipal.customer("user-a", "cust-a"))));

    @Test
    void shouldSearchAsThePrincipalOfTheRuntimeUser() {
        ToolResult result =
                tool.execute(
                        new ToolInvocationContext("agent-1", "user-a", "session-1"),
                        Map.of("query", "季度申报"));

        assertTrue(result.success());
        assertEquals(List.of(AccessPrincipal.customer("user-a", "cust-a")), searchedAs);
    }

    @Test
    void shouldDenyUnregisteredUserWithoutSearching() {
        ToolResult result =
                tool.execute(
                        new ToolInvocationContext("agent-1", "stranger", "session-1"),
                        Map.of("query", "季度申报"));

        assertFalse(result.success());
        assertEquals("ACCESS_DENIED", result.failureCode());
        assertFalse(result.retryable());
        assertTrue(searchedAs.isEmpty());
    }

    @Test
    void gatewayFailureKeepsItsCodeRetryabilityAndRequestId() {
        assertGatewayFailure(
                new ModelCallFailedException("RATE_LIMITED", true, "gw-1", "rate limited"),
                "MODEL_RATE_LIMITED",
                true,
                "gw-1");
        // An unknown upstream outcome may already be billed: non-retryable, so tool governance
        // blocks the agent from repeating the identical search.
        assertGatewayFailure(
                new IllegalStateException(
                        "judge failed",
                        new ModelCallFailedException(
                                "UPSTREAM_OUTCOME_UNKNOWN", false, "gw-2", "unknown outcome")),
                "MODEL_UPSTREAM_OUTCOME_UNKNOWN",
                false,
                "gw-2");
    }

    @Test
    void successReportsTheGatewayCallsBehindTheSearch() {
        KnowledgeRetriever traced =
                new KnowledgeRetriever() {
                    @Override
                    public List<KnowledgeSearchHit> search(
                            String query, int topK, AccessPrincipal principal) {
                        return List.of();
                    }

                    @Override
                    public KnowledgeRetrievalResult searchDetailed(
                            String query, int topK, AccessPrincipal principal) {
                        return new KnowledgeRetrievalResult(
                                List.of(),
                                null,
                                null,
                                null,
                                null,
                                new KnowledgeModelCallDiagnostics(
                                        "kr-1",
                                        List.of(
                                                new KnowledgeModelCall(
                                                        "ANSWERABILITY",
                                                        "gw-3",
                                                        "DASHSCOPE",
                                                        "qwen-plus",
                                                        null))));
                    }
                };

        ToolResult result = toolOver(traced).execute(context(), Map.of("query", "季度申报"));

        assertTrue(result.success());
        assertEquals("kr-1", result.data().get("modelCorrelationId"));
        List<?> calls = (List<?>) result.data().get("modelCalls");
        assertEquals("qwen-plus", ((Map<?, ?>) calls.get(0)).get("servedModel"));
    }

    @Test
    void modelCannotSupplyAnIdentity() {
        Map<String, Object> schema = tool.inputSchema();

        assertEquals(false, schema.get("additionalProperties"));
        assertEquals(
                List.of("query", "topK"),
                ((Map<?, ?>) schema.get("properties")).keySet().stream().sorted().toList());
    }

    private static void assertGatewayFailure(
            RuntimeException thrown, String failureCode, boolean retryable, String requestId) {
        KnowledgeRetriever failing =
                (query, topK, principal) -> {
                    throw thrown;
                };

        ToolResult result = toolOver(failing).execute(context(), Map.of("query", "季度申报"));

        assertFalse(result.success());
        assertEquals(failureCode, result.failureCode());
        assertEquals(retryable, result.retryable());
        assertEquals(requestId, result.data().get("gatewayRequestId"));
    }

    private static KnowledgeSearchTool toolOver(KnowledgeRetriever retriever) {
        return new KnowledgeSearchTool(
                retriever,
                new ConfiguredPrincipalDirectory(
                        Map.of("user-a", AccessPrincipal.customer("user-a", "cust-a"))));
    }

    private static ToolInvocationContext context() {
        return new ToolInvocationContext("agent-1", "user-a", "session-1");
    }
}
