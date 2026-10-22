package com.agentforge.infrastructure.tool;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.core.security.AccessPrincipal;
import com.agentforge.infrastructure.security.ConfiguredPrincipalDirectory;
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
    void modelCannotSupplyAnIdentity() {
        Map<String, Object> schema = tool.inputSchema();

        assertEquals(false, schema.get("additionalProperties"));
        assertEquals(
                List.of("query", "topK"),
                ((Map<?, ?>) schema.get("properties")).keySet().stream().sorted().toList());
    }
}
