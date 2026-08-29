package com.agentforge.runtime;

import com.agentforge.runtime.ToolGovernanceState.ToolGovernanceDecision;
import com.agentforge.runtime.confirmation.ToolConfirmation;
import com.agentforge.runtime.confirmation.ToolConfirmationBinding;
import com.agentforge.runtime.confirmation.ToolConfirmationResolution;
import com.agentforge.runtime.confirmation.ToolConfirmationService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

final class AgentScopeToolAdapter extends ToolBase {

    private final String agentId;
    private final AgentTool delegate;
    private final ToolExecutionPolicy executionPolicy;
    private final ToolConfirmationService confirmationService;

    AgentScopeToolAdapter(String agentId, AgentTool delegate) {
        this(agentId, delegate, new BaselineToolExecutionPolicy(ToolPolicyOptions.defaults()), null);
    }

    AgentScopeToolAdapter(String agentId, AgentTool delegate, ToolExecutionPolicy executionPolicy) {
        this(agentId, delegate, executionPolicy, null);
    }

    AgentScopeToolAdapter(
            String agentId,
            AgentTool delegate,
            ToolExecutionPolicy executionPolicy,
            ToolConfirmationService confirmationService) {
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
        this.confirmationService = confirmationService;
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
                                            runtimeContext != null ? runtimeContext.getSessionId() : null,
                                            telemetryBuffer);

                            String toolCallId =
                                    param.getToolUseBlock() == null ? null : param.getToolUseBlock().getId();
                            Map<String, Object> input =
                                    param.getInput() == null ? Map.of() : param.getInput();
                            String fingerprint = ToolGovernanceState.fingerprint(delegate.name(), input);

                            ToolPolicyEvaluation baselinePolicy =
                                    executionPolicy.evaluate(
                                            new ToolPolicyContext(
                                                    agentId,
                                                    invocationContext.userId(),
                                                    invocationContext.sessionId(),
                                                    delegate.name(),
                                                    delegate.readOnly(),
                                                    delegate.riskLevel(),
                                                    input));

                            ConfirmationDecision confirmationDecision =
                                    resolveConfirmation(
                                            baselinePolicy,
                                            runtimeContext,
                                            toolCallId,
                                            invocationContext,
                                            fingerprint,
                                            input);
                            ToolPolicyEvaluation effectivePolicy = confirmationDecision.effectivePolicy();

                            ToolGovernanceState governance =
                                    runtimeContext == null
                                            ? null
                                            : runtimeContext.get(ToolGovernanceState.class);
                            ToolGovernanceDecision governanceDecision =
                                    !effectivePolicy.decision().executable()
                                            ? ToolGovernanceDecision.skipped(fingerprint, "policy_preempted")
                                            : governance == null
                                                    ? ToolGovernanceDecision.allow(fingerprint)
                                                    : governance.beforeInvocation(
                                                            toolCallId, delegate.name(), input);

                            long startedNanos = System.nanoTime();
                            try {
                                ToolResult result =
                                        decideAndExecute(
                                                effectivePolicy,
                                                governanceDecision,
                                                confirmationDecision.confirmation(),
                                                invocationContext,
                                                input);

                                if (governance != null) {
                                    governance.recordResult(
                                            toolCallId,
                                            governanceDecision,
                                            effectivePolicy,
                                            delegate.riskLevel(),
                                            delegate.readOnly(),
                                            result,
                                            elapsedMillis(startedNanos),
                                            telemetryBuffer.latestOutboundHttp(),
                                            confirmationDecision.confirmation());
                                }
                                return toToolResultBlock(param, result);
                            } catch (RuntimeException exception) {
                                if (governance != null) {
                                    governance.recordException(
                                            toolCallId,
                                            governanceDecision,
                                            effectivePolicy,
                                            delegate.riskLevel(),
                                            delegate.readOnly(),
                                            elapsedMillis(startedNanos),
                                            telemetryBuffer.latestOutboundHttp(),
                                            confirmationDecision.confirmation());
                                }
                                throw exception;
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ConfirmationDecision resolveConfirmation(
            ToolPolicyEvaluation baselinePolicy,
            RuntimeContext runtimeContext,
            String toolCallId,
            ToolInvocationContext invocationContext,
            String fingerprint,
            Map<String, Object> input) {
        if (baselinePolicy.decision() != ToolPolicyDecision.REQUIRE_CONFIRMATION) {
            return new ConfirmationDecision(baselinePolicy, null);
        }
        if (confirmationService == null
                || runtimeContext == null
                || toolCallId == null
                || toolCallId.isBlank()) {
            return new ConfirmationDecision(baselinePolicy, null);
        }
        ToolExecutionIdentity identity = runtimeContext.get(ToolExecutionIdentity.class);
        if (identity == null) {
            return new ConfirmationDecision(baselinePolicy, null);
        }

        ToolConfirmationResolution resolution =
                confirmationService.resolve(
                        identity.executionId(),
                        toolCallId,
                        new ToolConfirmationBinding(
                                agentId,
                                invocationContext.userId(),
                                invocationContext.sessionId(),
                                delegate.name(),
                                fingerprint),
                        delegate.confirmationSummary(input));
        if (resolution.authorized()) {
            return new ConfirmationDecision(
                    ToolPolicyEvaluation.allow("approved_confirmation"),
                    resolution.confirmation());
        }
        return new ConfirmationDecision(baselinePolicy, resolution.confirmation());
    }

    private ToolResult decideAndExecute(
            ToolPolicyEvaluation effectivePolicy,
            ToolGovernanceDecision governanceDecision,
            ToolConfirmation confirmation,
            ToolInvocationContext context,
            Map<String, Object> input) {
        if (effectivePolicy.decision() == ToolPolicyDecision.DENY) {
            return ToolResult.failure(
                    "tool invocation denied by execution policy", "POLICY_DENIED", false);
        }
        if (effectivePolicy.decision() == ToolPolicyDecision.REQUIRE_CONFIRMATION) {
            if (confirmation == null) {
                return ToolResult.failure(
                        "tool invocation requires confirmation, but durable confirmation is unavailable",
                        "CONFIRMATION_UNAVAILABLE",
                        false);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("confirmationId", confirmation.id());
            data.put("status", confirmation.status().name());
            data.put("expiresAt", confirmation.expiresAt().toString());
            data.put("toolName", confirmation.toolName());
            data.put("summary", confirmation.summary());
            return ToolResult.failure(
                    "tool invocation requires user confirmation before execution",
                    data,
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
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
    }

    private record ConfirmationDecision(
            ToolPolicyEvaluation effectivePolicy,
            ToolConfirmation confirmation) {}
}
