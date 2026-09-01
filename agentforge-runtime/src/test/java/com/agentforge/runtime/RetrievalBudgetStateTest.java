package com.agentforge.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.tool.AgentTool;
import com.agentforge.tool.ToolInvocationContext;
import com.agentforge.tool.ToolResult;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RetrievalBudgetStateTest {

    @Test
    void shouldAllowThreeKnowledgeCallsThenBlockFourthRegardlessOfArguments() {
        RetrievalBudgetState state =
                new RetrievalBudgetState(new RetrievalBudgetOptions(true, 3));

        assertTrue(
                state.beforeInvocation("call-1", "search_knowledge").allowed());
        assertTrue(
                state.beforeInvocation("call-2", "search_knowledge").allowed());
        assertTrue(
                state.beforeInvocation("call-3", "search_knowledge").allowed());

        RetrievalBudgetState.RetrievalBudgetDecision fourth =
                state.beforeInvocation("call-4", "search_knowledge");

        assertFalse(fourth.allowed());
        assertEquals("BLOCK", fourth.decision());
        assertEquals("retrieval_budget_exhausted", fourth.reason());
        assertEquals(3, fourth.limit());
        assertEquals(3, fourth.used());
        assertEquals(0, fourth.remaining());
    }

    @Test
    void nonKnowledgeToolShouldNotConsumeBudget() {
        RetrievalBudgetState state =
                new RetrievalBudgetState(new RetrievalBudgetOptions(true, 1));

        assertNull(state.beforeInvocation("device-1", "query_device_status"));
        assertEquals(0, state.usedCalls());

        assertTrue(
                state.beforeInvocation("knowledge-1", "search_knowledge").allowed());
        assertEquals(1, state.usedCalls());
    }

    @Test
    void adapterMustBlockBeforeKnowledgeDelegateExecution() {
        AtomicInteger executions = new AtomicInteger();
        AgentTool tool = knowledgeTool(executions);
        AgentScopeToolAdapter adapter = new AgentScopeToolAdapter("agent", tool);

        ToolGovernanceState governance =
                new ToolGovernanceState(
                        new ToolGovernanceOptions(true, true, 32));
        RetrievalBudgetState budget =
                new RetrievalBudgetState(
                        new RetrievalBudgetOptions(true, 3));
        RuntimeContext context =
                runtimeContext(governance, budget);

        for (int i = 1; i <= 3; i++) {
            ToolResultBlock result =
                    adapter.callAsync(
                                    param(
                                            "call-" + i,
                                            "search_knowledge",
                                            Map.of("query", "query-" + i),
                                            context))
                            .block();
            assertNotNull(result);
            assertEquals(ToolResultState.SUCCESS, result.getState());
        }

        ToolResultBlock fourth =
                adapter.callAsync(
                                param(
                                        "call-4",
                                        "search_knowledge",
                                        Map.of("query", "different-query"),
                                        context))
                        .block();

        assertNotNull(fourth);
        assertEquals(ToolResultState.ERROR, fourth.getState());
        assertEquals(3, executions.get());

        RetrievalBudgetState.RetrievalBudgetDecision decision =
                budget.decision("call-4");
        assertNotNull(decision);
        assertFalse(decision.allowed());
        assertEquals(3, decision.used());
        assertEquals(0, decision.remaining());

        ToolGovernanceState.ToolInvocationTelemetry telemetry =
                governance.telemetry("call-4");
        assertNotNull(telemetry);
        assertEquals(
                RetrievalBudgetState.FAILURE_RETRIEVAL_BUDGET_EXHAUSTED,
                telemetry.failureCode());
        assertEquals(ToolGovernanceState.DECISION_ALLOW, telemetry.governanceDecision());
    }

    @Test
    void budgetFailureMustNotPoisonRepeatedCallGovernance() {
        AtomicInteger executions = new AtomicInteger();
        AgentTool tool = knowledgeTool(executions);
        AgentScopeToolAdapter adapter = new AgentScopeToolAdapter("agent", tool);

        ToolGovernanceState governance =
                new ToolGovernanceState(
                        new ToolGovernanceOptions(true, true, 32));
        RetrievalBudgetState budget =
                new RetrievalBudgetState(
                        new RetrievalBudgetOptions(true, 1));
        RuntimeContext context =
                runtimeContext(governance, budget);
        Map<String, Object> input = Map.of("query", "same");

        adapter.callAsync(param("call-1", "search_knowledge", input, context)).block();
        adapter.callAsync(param("call-2", "search_knowledge", input, context)).block();
        adapter.callAsync(param("call-3", "search_knowledge", input, context)).block();

        assertEquals(1, executions.get());
        assertEquals(
                ToolGovernanceState.DECISION_ALLOW,
                governance.telemetry("call-3").governanceDecision());
        assertEquals(
                RetrievalBudgetState.DECISION_BLOCK,
                budget.decision("call-3").decision());
    }

    @Test
    void stateMustBeIsolatedPerExecution() {
        RetrievalBudgetState first =
                new RetrievalBudgetState(
                        new RetrievalBudgetOptions(true, 1));
        assertTrue(first.beforeInvocation("call-1", "search_knowledge").allowed());
        assertFalse(first.beforeInvocation("call-2", "search_knowledge").allowed());

        RetrievalBudgetState second =
                new RetrievalBudgetState(
                        new RetrievalBudgetOptions(true, 1));
        assertTrue(second.beforeInvocation("call-1", "search_knowledge").allowed());
    }

    private static AgentTool knowledgeTool(AtomicInteger executions) {
        return new AgentTool() {
            @Override
            public String name() {
                return "search_knowledge";
            }

            @Override
            public String description() {
                return "knowledge test tool";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "query",
                                Map.of("type", "string")));
            }

            @Override
            public ToolResult execute(
                    ToolInvocationContext context,
                    Map<String, Object> arguments) {
                executions.incrementAndGet();
                return ToolResult.success("ok", Map.of());
            }
        };
    }

    private static RuntimeContext runtimeContext(
            ToolGovernanceState governance,
            RetrievalBudgetState budget) {
        return RuntimeContext.builder()
                .userId("user")
                .sessionId("session")
                .put(ToolGovernanceState.class, governance)
                .put(RetrievalBudgetState.class, budget)
                .build();
    }

    private static ToolCallParam param(
            String callId,
            String toolName,
            Map<String, Object> input,
            RuntimeContext context) {
        return ToolCallParam.builder()
                .toolUseBlock(new ToolUseBlock(callId, toolName, input))
                .input(input)
                .runtimeContext(context)
                .build();
    }
}
