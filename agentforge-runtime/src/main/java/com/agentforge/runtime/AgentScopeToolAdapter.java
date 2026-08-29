package com.agentforge.runtime;

import com.agentforge.runtime.ToolGovernanceState.ToolGovernanceDecision;
import com.agentforge.tool.AgentTool;
import com.agentforge.tool.ToolInvocationContext;
import com.agentforge.tool.ToolResult;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

final class AgentScopeToolAdapter extends ToolBase {

    private final String agentId;
    private final AgentTool delegate;
    private final ToolExecutionPolicy executionPolicy;

    AgentScopeToolAdapter(String agentId, AgentTool delegate) {
        this(
                agentId,
                delegate,
                new BaselineToolExecutionPolicy(ToolPolicyOptions.defaults()));
    }

    AgentScopeToolAdapter(
            String agentId, AgentTool delegate, ToolExecutionPolicy executionPolicy) {
        super(
                ToolBase.builder()
                        .name(delegate.name())
                        .description(delegate.description())
                        .inputSchema(delegate.inputSchema())
                        .readOnly(delegate.readOnly())
                        .concurrencySafe(delegate.concurrencySafe()));
        this.agentId = agentId;
        this.delegate = delegate;
        this.executionPolicy =
                executionPolicy == null
                        ? new BaselineToolExecutionPolicy(ToolPolicyOptions.defaults())
                        : executionPolicy;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, PermissionContextState context) {
        if (delegate.readOnly()) {
            return Mono.just(PermissionDecision.allow("AgentForge read-only business tool."));
        }
        return Mono.just(
                PermissionDecision.passthrough(
                        "Non-read-only AgentForge tools must follow AgentScope permission rules."));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(
                        () -> {
                            RuntimeContext runtimeContext = param.getRuntimeContext();
                            ToolTelemetryBuffer telemetryBuffer = new ToolTelemetryBuffer();
                            ToolInvocationContext invocationContext =
                                    new ToolInvocationContext(
                                            agentId,
                                            runtimeContext != null ? runtimeContext.getUserId() : null,
                                            runtimeContext != null
                                                    ? runtimeContext.getSessionId()
                                                    : null,
                                            telemetryBuffer);

                            String toolCallId =
                                    param.getToolUseBlock() == null
                                            ? null
                                            : param.getToolUseBlock().getId();
                            Map<String, Object> input =
                                    param.getInput() == null ? Map.of() : param.getInput();

                            ToolPolicyEvaluation policy =
                                    executionPolicy.evaluate(
                                            new ToolPolicyContext(
                                                    agentId,
                                                    invocationContext.userId(),
                                                    invocationContext.sessionId(),
                                                    delegate.name(),
                                                    delegate.readOnly(),
                                                    delegate.riskLevel(),
                                                    input));

                            ToolGovernanceState governance =
                                    runtimeContext == null
                                            ? null
                                            : runtimeContext.get(ToolGovernanceState.class);
                            String fingerprint =
                                    ToolGovernanceState.fingerprint(delegate.name(), input);
                            ToolGovernanceDecision governanceDecision =
                                    !policy.decision().executable()
                                            ? ToolGovernanceDecision.skipped(
                                                    fingerprint, "policy_preempted")
                                            : governance == null
                                                    ? ToolGovernanceDecision.allow(fingerprint)
                                                    : governance.beforeInvocation(
                                                            toolCallId,
                                                            delegate.name(),
                                                            input);

                            long startedNanos = System.nanoTime();
                            try {
                                ToolResult result =
                                        decideAndExecute(
                                                policy,
                                                governanceDecision,
                                                invocationContext,
                                                input);

                                if (governance != null) {
                                    governance.recordResult(
                                            toolCallId,
                                            governanceDecision,
                                            policy,
                                            delegate.riskLevel(),
                                            delegate.readOnly(),
                                            result,
                                            elapsedMillis(startedNanos),
                                            telemetryBuffer.latestOutboundHttp());
                                }
                                return toToolResultBlock(param, result);
                            } catch (RuntimeException exception) {
                                if (governance != null) {
                                    governance.recordException(
                                            toolCallId,
                                            governanceDecision,
                                            policy,
                                            delegate.riskLevel(),
                                            delegate.readOnly(),
                                            elapsedMillis(startedNanos),
                                            telemetryBuffer.latestOutboundHttp());
                                }
                                throw exception;
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ToolResult decideAndExecute(
            ToolPolicyEvaluation policy,
            ToolGovernanceDecision governanceDecision,
            ToolInvocationContext context,
            Map<String, Object> input) {
        if (policy.decision() == ToolPolicyDecision.DENY) {
            return ToolResult.failure(
                    "tool invocation denied by execution policy",
                    "POLICY_DENIED",
                    false);
        }
        if (policy.decision() == ToolPolicyDecision.REQUIRE_CONFIRMATION) {
            return ToolResult.failure(
                    "tool invocation requires user confirmation before execution",
                    "CONFIRMATION_REQUIRED",
                    false);
        }
        if (!governanceDecision.allowed()) {
            String previousCode =
                    governanceDecision.blockedByFailureCode() == null
                            ? "NON_RETRYABLE_FAILURE"
                            : governanceDecision.blockedByFailureCode();
            return ToolResult.failure(
                    "tool invocation blocked by governance: identical arguments already failed with "
                            + previousCode
                            + "; do not retry unchanged arguments",
                    ToolGovernanceState.FAILURE_REPEATED_NON_RETRYABLE_CALL,
                    false);
        }
        return delegate.execute(context, input);
    }

    private ToolResultBlock toToolResultBlock(ToolCallParam param, ToolResult result) {
        return ToolResultBlock.builder()
                .id(param.getToolUseBlock().getId())
                .name(delegate.name())
                .output(List.of(TextBlock.builder().text(result.toModelText()).build()))
                .state(result.success() ? ToolResultState.SUCCESS : ToolResultState.ERROR)
                .build();
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(
                0L,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
    }
}
