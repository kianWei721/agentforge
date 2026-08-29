package com.agentforge.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.agentforge.tool.AgentTool;
import com.agentforge.tool.ToolInvocationContext;
import com.agentforge.tool.ToolResult;
import com.agentforge.runtime.confirmation.ToolConfirmation;
import com.agentforge.runtime.confirmation.ToolConfirmationRepository;
import com.agentforge.runtime.confirmation.ToolConfirmationService;
import com.agentforge.runtime.confirmation.ToolConfirmationStatus;
import com.agentforge.tool.ToolRiskLevel;
import com.agentforge.tool.telemetry.OutboundHttpTelemetry;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ToolGovernanceStateTest {

    @Test
    void shouldBlockRepeatedIdenticalCallAfterNonRetryableFailure() {
        ToolGovernanceState state =
                new ToolGovernanceState(new ToolGovernanceOptions(true, true, 32));

        ToolGovernanceState.ToolGovernanceDecision first =
                state.beforeInvocation("call-1", "query_device_status", Map.of("deviceId", "999"));
        assertTrue(first.allowed());

        state.recordResult(
                "call-1",
                first,
                ToolResult.failure(
                        "device status query unavailable: FORBIDDEN",
                        "FORBIDDEN",
                        false),
                12L);

        ToolGovernanceState.ToolGovernanceDecision second =
                state.beforeInvocation("call-2", "query_device_status", Map.of("deviceId", "999"));

        assertFalse(second.allowed());
        assertEquals("FORBIDDEN", second.blockedByFailureCode());
        assertEquals("call-1", second.blockedByToolCallId());
    }

    @Test
    void shouldAllowRepeatedCallAfterRetryableFailure() {
        ToolGovernanceState state =
                new ToolGovernanceState(new ToolGovernanceOptions(true, true, 32));

        ToolGovernanceState.ToolGovernanceDecision first =
                state.beforeInvocation("call-1", "query_device_status", Map.of("deviceId", "367"));
        state.recordResult(
                "call-1",
                first,
                ToolResult.failure(
                        "device status query unavailable: TIMEOUT",
                        "TIMEOUT",
                        true),
                3000L);

        assertTrue(
                state.beforeInvocation(
                                "call-2",
                                "query_device_status",
                                Map.of("deviceId", "367"))
                        .allowed());
    }

    @Test
    void shouldCanonicalizeEquivalentArgumentMaps() {
        ToolGovernanceState state =
                new ToolGovernanceState(new ToolGovernanceOptions(true, true, 32));

        Map<String, Object> firstArgs = new LinkedHashMap<>();
        firstArgs.put("deviceId", "999");
        firstArgs.put("options", Map.of("b", 2, "a", 1));

        Map<String, Object> secondArgs = new LinkedHashMap<>();
        secondArgs.put("options", Map.of("a", 1, "b", 2));
        secondArgs.put("deviceId", "999");

        ToolGovernanceState.ToolGovernanceDecision first =
                state.beforeInvocation("call-1", "query_device_status", firstArgs);
        state.recordResult(
                "call-1",
                first,
                ToolResult.failure("forbidden", "FORBIDDEN", false),
                5L);

        ToolGovernanceState.ToolGovernanceDecision second =
                state.beforeInvocation("call-2", "query_device_status", secondArgs);

        assertFalse(second.allowed());
        assertEquals(first.argumentFingerprint(), second.argumentFingerprint());
    }

    @Test
    void adapterShouldNotExecuteDelegateTwiceAfterNonRetryableFailure() {
        AtomicInteger executions = new AtomicInteger();
        AgentTool tool =
                new AgentTool() {
                    @Override
                    public String name() {
                        return "test_tool";
                    }

                    @Override
                    public String description() {
                        return "test";
                    }

                    @Override
                    public Map<String, Object> inputSchema() {
                        return Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of("value", Map.of("type", "string")),
                                "required",
                                List.of("value"));
                    }

                    @Override
                    public ToolResult execute(
                            ToolInvocationContext context, Map<String, Object> arguments) {
                        executions.incrementAndGet();
                        return ToolResult.failure("forbidden", "FORBIDDEN", false);
                    }
                };

        ToolGovernanceState state =
                new ToolGovernanceState(new ToolGovernanceOptions(true, true, 32));
        RuntimeContext context =
                RuntimeContext.builder()
                        .userId("user")
                        .sessionId("session")
                        .put(ToolGovernanceState.class, state)
                        .build();
        AgentScopeToolAdapter adapter = new AgentScopeToolAdapter("agent", tool);

        Map<String, Object> input = Map.of("value", "same");
        adapter.callAsync(param("call-1", "test_tool", input, context)).block();
        adapter.callAsync(param("call-2", "test_tool", input, context)).block();

        assertEquals(1, executions.get());
        ToolGovernanceState.ToolInvocationTelemetry second = state.telemetry("call-2");
        assertNotNull(second);
        assertEquals("BLOCK", second.governanceDecision());
        assertEquals(
                ToolGovernanceState.FAILURE_REPEATED_NON_RETRYABLE_CALL,
                second.failureCode());
        assertEquals("call-1", second.blockedByToolCallId());
        assertEquals("FORBIDDEN", second.blockedByFailureCode());
    }

    @Test
    void shouldCarrySanitizedOutboundHttpTelemetry() {
        ToolGovernanceState state =
                new ToolGovernanceState(new ToolGovernanceOptions(true, true, 32));
        ToolGovernanceState.ToolGovernanceDecision decision =
                state.beforeInvocation("call-http", "query_device_status", Map.of("deviceId", "367"));

        state.recordResult(
                "call-http",
                decision,
                ToolResult.success("ok", Map.of()),
                33L,
                new OutboundHttpTelemetry(
                        "HTTP",
                        "yudao-device-web",
                        2,
                        200,
                        200,
                        181L,
                        "SUCCESS"));

        ToolGovernanceState.ToolInvocationTelemetry telemetry = state.telemetry("call-http");
        assertNotNull(telemetry);
        assertEquals("HTTP", telemetry.outboundProtocol());
        assertEquals("yudao-device-web", telemetry.downstreamSystem());
        assertEquals(2, telemetry.outboundAttempts());
        assertEquals(200, telemetry.httpStatus());
        assertEquals(200, telemetry.businessCode());
        assertEquals(181L, telemetry.downstreamDurationMs());
        assertEquals("SUCCESS", telemetry.outboundOutcome());
    }

    @Test
    void adapterShouldCarryToolTelemetryFromInvocationContext() {
        AgentTool tool =
                new AgentTool() {
                    @Override
                    public String name() {
                        return "telemetry_tool";
                    }

                    @Override
                    public String description() {
                        return "telemetry test";
                    }

                    @Override
                    public Map<String, Object> inputSchema() {
                        return Map.of("type", "object", "properties", Map.of());
                    }

                    @Override
                    public ToolResult execute(
                            ToolInvocationContext context, Map<String, Object> arguments) {
                        context.telemetry()
                                .recordOutboundHttp(
                                        new OutboundHttpTelemetry(
                                                "HTTP",
                                                "test-downstream",
                                                1,
                                                200,
                                                200,
                                                9L,
                                                "SUCCESS"));
                        return ToolResult.success("ok", Map.of());
                    }
                };

        ToolGovernanceState state =
                new ToolGovernanceState(new ToolGovernanceOptions(true, true, 32));
        RuntimeContext context =
                RuntimeContext.builder()
                        .userId("user")
                        .sessionId("session")
                        .put(ToolGovernanceState.class, state)
                        .build();

        AgentScopeToolAdapter adapter = new AgentScopeToolAdapter("agent", tool);
        adapter.callAsync(param("call-telemetry", "telemetry_tool", Map.of(), context)).block();

        ToolGovernanceState.ToolInvocationTelemetry telemetry =
                state.telemetry("call-telemetry");
        assertNotNull(telemetry);
        assertEquals("HTTP", telemetry.outboundProtocol());
        assertEquals("test-downstream", telemetry.downstreamSystem());
        assertEquals(1, telemetry.outboundAttempts());
        assertEquals(200, telemetry.httpStatus());
        assertEquals(200, telemetry.businessCode());
        assertEquals(9L, telemetry.downstreamDurationMs());
        assertEquals("SUCCESS", telemetry.outboundOutcome());
    }

    @Test
    void lowRiskReadOnlyToolShouldBeAllowedByBaselinePolicy() {
        AtomicInteger executions = new AtomicInteger();
        AgentTool tool = policyTestTool(true, ToolRiskLevel.LOW, executions);
        AgentScopeToolAdapter adapter =
                new AgentScopeToolAdapter(
                        "agent",
                        tool,
                        new BaselineToolExecutionPolicy(ToolPolicyOptions.defaults()));

        ToolResultBlock result =
                adapter.callAsync(
                                param(
                                        "call-policy-low",
                                        "policy_test_tool",
                                        Map.of(),
                                        runtimeContext(new ToolGovernanceState(
                                                new ToolGovernanceOptions(true, true, 32)))))
                        .block();

        assertNotNull(result);
        assertEquals(1, executions.get());
    }

    @Test
    void writeToolShouldRequireConfirmationBeforeDelegateExecution() {
        AtomicInteger executions = new AtomicInteger();
        ToolGovernanceState state =
                new ToolGovernanceState(new ToolGovernanceOptions(true, true, 32));
        AgentTool tool = policyTestTool(false, ToolRiskLevel.HIGH, executions);
        AgentScopeToolAdapter adapter =
                new AgentScopeToolAdapter(
                        "agent",
                        tool,
                        new BaselineToolExecutionPolicy(ToolPolicyOptions.defaults()));

        adapter.callAsync(
                        param(
                                "call-policy-write",
                                "policy_test_tool",
                                Map.of(),
                                runtimeContext(state)))
                .block();

        assertEquals(0, executions.get());
        ToolGovernanceState.ToolInvocationTelemetry telemetry =
                state.telemetry("call-policy-write");
        assertNotNull(telemetry);
        assertEquals("HIGH", telemetry.toolRiskLevel());
        assertFalse(telemetry.toolReadOnly());
        assertEquals("REQUIRE_CONFIRMATION", telemetry.policyDecision());
        assertEquals("write_tool_requires_confirmation", telemetry.policyReason());
        assertEquals(0, telemetry.outboundAttempts());
        assertEquals("CONFIRMATION_REQUIRED", telemetry.outboundOutcome());
    }

    @Test
    void customPolicyDenyShouldPreventDelegateExecution() {
        AtomicInteger executions = new AtomicInteger();
        ToolGovernanceState state =
                new ToolGovernanceState(new ToolGovernanceOptions(true, true, 32));
        AgentTool tool = policyTestTool(true, ToolRiskLevel.MEDIUM, executions);
        AgentScopeToolAdapter adapter =
                new AgentScopeToolAdapter(
                        "agent",
                        tool,
                        context -> ToolPolicyEvaluation.deny("test_policy_deny"));

        adapter.callAsync(
                        param(
                                "call-policy-deny",
                                "policy_test_tool",
                                Map.of(),
                                runtimeContext(state)))
                .block();

        assertEquals(0, executions.get());
        ToolGovernanceState.ToolInvocationTelemetry telemetry =
                state.telemetry("call-policy-deny");
        assertNotNull(telemetry);
        assertEquals("DENY", telemetry.policyDecision());
        assertEquals("test_policy_deny", telemetry.policyReason());
        assertEquals("POLICY_DENIED", telemetry.outboundOutcome());
    }

    @Test
    void approvedConfirmationShouldAuthorizeExactlyOneMatchingWriteInvocation() {
        AtomicInteger executions = new AtomicInteger();
        AgentTool writeTool = policyTestTool(false, ToolRiskLevel.HIGH, executions);
        FakeConfirmationRepository repository = new FakeConfirmationRepository();
        ToolConfirmationService confirmationService =
                new ToolConfirmationService(
                        repository,
                        Duration.ofMinutes(5),
                        Clock.fixed(
                                Instant.parse("2026-08-29T07:00:00Z"),
                                ZoneOffset.UTC));
        AgentScopeToolAdapter adapter =
                new AgentScopeToolAdapter(
                        "agent",
                        writeTool,
                        new BaselineToolExecutionPolicy(ToolPolicyOptions.defaults()),
                        confirmationService);

        Map<String, Object> input = Map.of("value", "same");
        ToolGovernanceState firstState =
                new ToolGovernanceState(new ToolGovernanceOptions(true, true, 32));
        RuntimeContext firstContext =
                RuntimeContext.builder()
                        .userId("user")
                        .sessionId("session")
                        .put(ToolGovernanceState.class, firstState)
                        .put(ToolExecutionIdentity.class, new ToolExecutionIdentity("execution-1"))
                        .build();

        adapter.callAsync(
                        param(
                                "call-1",
                                "policy_test_tool",
                                input,
                                firstContext))
                .block();

        assertEquals(0, executions.get());
        ToolGovernanceState.ToolInvocationTelemetry first =
                firstState.telemetry("call-1");
        assertNotNull(first);
        assertEquals("REQUIRE_CONFIRMATION", first.policyDecision());
        assertEquals("PENDING", first.confirmationStatus());
        assertNotNull(first.confirmationId());
        assertEquals(0, first.outboundAttempts());

        ToolConfirmation approved =
                confirmationService.approve(first.confirmationId(), "user");
        assertEquals(ToolConfirmationStatus.APPROVED, approved.status());

        ToolGovernanceState secondState =
                new ToolGovernanceState(new ToolGovernanceOptions(true, true, 32));
        RuntimeContext secondContext =
                RuntimeContext.builder()
                        .userId("user")
                        .sessionId("session")
                        .put(ToolGovernanceState.class, secondState)
                        .put(ToolExecutionIdentity.class, new ToolExecutionIdentity("execution-2"))
                        .build();

        adapter.callAsync(
                        param(
                                "call-2",
                                "policy_test_tool",
                                input,
                                secondContext))
                .block();

        assertEquals(1, executions.get());
        ToolGovernanceState.ToolInvocationTelemetry second =
                secondState.telemetry("call-2");
        assertNotNull(second);
        assertEquals("ALLOW", second.policyDecision());
        assertEquals("approved_confirmation", second.policyReason());
        assertEquals(first.confirmationId(), second.confirmationId());
        assertEquals("CONSUMED", second.confirmationStatus());

        ToolGovernanceState thirdState =
                new ToolGovernanceState(new ToolGovernanceOptions(true, true, 32));
        RuntimeContext thirdContext =
                RuntimeContext.builder()
                        .userId("user")
                        .sessionId("session")
                        .put(ToolGovernanceState.class, thirdState)
                        .put(ToolExecutionIdentity.class, new ToolExecutionIdentity("execution-3"))
                        .build();

        adapter.callAsync(
                        param(
                                "call-3",
                                "policy_test_tool",
                                input,
                                thirdContext))
                .block();

        assertEquals(1, executions.get());
        ToolGovernanceState.ToolInvocationTelemetry third =
                thirdState.telemetry("call-3");
        assertNotNull(third);
        assertEquals("REQUIRE_CONFIRMATION", third.policyDecision());
        assertEquals("PENDING", third.confirmationStatus());
        assertNotEquals(first.confirmationId(), third.confirmationId());
    }

    @Test
    void stateMustBeIsolatedPerAgentCall() {
        ToolGovernanceState firstState =
                new ToolGovernanceState(new ToolGovernanceOptions(true, true, 32));
        ToolGovernanceState.ToolGovernanceDecision first =
                firstState.beforeInvocation("call-1", "test", Map.of("x", 1));
        firstState.recordResult(
                "call-1", first, ToolResult.failure("bad", "BAD_REQUEST", false), 1L);

        ToolGovernanceState secondState =
                new ToolGovernanceState(new ToolGovernanceOptions(true, true, 32));
        assertTrue(
                secondState
                        .beforeInvocation("call-2", "test", Map.of("x", 1))
                        .allowed());
    }

    private static final class FakeConfirmationRepository
            implements ToolConfirmationRepository {

        private final Map<String, ToolConfirmation> byId = new HashMap<>();
        private ToolConfirmation active;

        @Override
        public ToolConfirmation createOrGetActive(
                ToolConfirmation draft, Instant now) {
            if (active != null
                    && active.status().active()
                    && active.expiresAt().isAfter(now)
                    && active.activeKey().equals(draft.activeKey())) {
                return active;
            }
            active = draft;
            byId.put(draft.id(), draft);
            return draft;
        }

        @Override
        public Optional<ToolConfirmation> findById(String confirmationId) {
            return Optional.ofNullable(byId.get(confirmationId));
        }

        @Override
        public ToolConfirmation approve(
                String confirmationId, String userId, Instant now) {
            ToolConfirmation current = byId.get(confirmationId);
            if (current == null) {
                throw new IllegalStateException("missing confirmation");
            }
            if (!current.userId().equals(userId)) {
                throw new IllegalStateException("wrong user");
            }
            ToolConfirmation approved =
                    copy(
                            current,
                            current.activeKey(),
                            ToolConfirmationStatus.APPROVED,
                            now,
                            null,
                            null,
                            null,
                            null);
            byId.put(approved.id(), approved);
            active = approved;
            return approved;
        }

        @Override
        public ToolConfirmation reject(
                String confirmationId, String userId, Instant now) {
            ToolConfirmation current = byId.get(confirmationId);
            ToolConfirmation rejected =
                    copy(
                            current,
                            null,
                            ToolConfirmationStatus.REJECTED,
                            current.approvedAt(),
                            now,
                            null,
                            null,
                            null);
            byId.put(rejected.id(), rejected);
            if (active != null && active.id().equals(rejected.id())) {
                active = null;
            }
            return rejected;
        }

        @Override
        public Optional<ToolConfirmation> consumeApproved(
                String activeKey,
                String consumedExecutionId,
                String consumedToolCallId,
                Instant now) {
            if (active == null
                    || active.status() != ToolConfirmationStatus.APPROVED
                    || !active.activeKey().equals(activeKey)
                    || !active.expiresAt().isAfter(now)) {
                return Optional.empty();
            }
            ToolConfirmation consumed =
                    copy(
                            active,
                            null,
                            ToolConfirmationStatus.CONSUMED,
                            active.approvedAt(),
                            active.rejectedAt(),
                            now,
                            consumedExecutionId,
                            consumedToolCallId);
            byId.put(consumed.id(), consumed);
            active = null;
            return Optional.of(consumed);
        }

        private static ToolConfirmation copy(
                ToolConfirmation source,
                String activeKey,
                ToolConfirmationStatus status,
                Instant approvedAt,
                Instant rejectedAt,
                Instant consumedAt,
                String consumedExecutionId,
                String consumedToolCallId) {
            return new ToolConfirmation(
                    source.id(),
                    activeKey,
                    source.requestedExecutionId(),
                    source.requestedToolCallId(),
                    consumedExecutionId,
                    consumedToolCallId,
                    source.agentId(),
                    source.userId(),
                    source.sessionId(),
                    source.toolName(),
                    source.argumentFingerprint(),
                    source.summary(),
                    status,
                    source.expiresAt(),
                    approvedAt,
                    rejectedAt,
                    consumedAt,
                    source.createdAt(),
                    consumedAt != null
                            ? consumedAt
                            : rejectedAt != null
                                    ? rejectedAt
                                    : approvedAt != null
                                            ? approvedAt
                                            : source.updatedAt());
        }
    }

    private static AgentTool policyTestTool(
            boolean readOnly, ToolRiskLevel riskLevel, AtomicInteger executions) {
        return new AgentTool() {
            @Override
            public String name() {
                return "policy_test_tool";
            }

            @Override
            public String description() {
                return "policy test";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public boolean readOnly() {
                return readOnly;
            }

            @Override
            public ToolRiskLevel riskLevel() {
                return riskLevel;
            }

            @Override
            public ToolResult execute(
                    ToolInvocationContext context, Map<String, Object> arguments) {
                executions.incrementAndGet();
                return ToolResult.success("ok", Map.of());
            }
        };
    }

    private static RuntimeContext runtimeContext(ToolGovernanceState state) {
        return RuntimeContext.builder()
                .userId("user")
                .sessionId("session")
                .put(ToolGovernanceState.class, state)
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
