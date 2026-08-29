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

    AgentScopeToolAdapter(String agentId, AgentTool delegate) {
        super(
                ToolBase.builder()
                        .name(delegate.name())
                        .description(delegate.description())
                        .inputSchema(delegate.inputSchema())
                        .readOnly(delegate.readOnly())
                        .concurrencySafe(delegate.concurrencySafe()));
        this.agentId = agentId;
        this.delegate = delegate;
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
                            ToolInvocationContext context =
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
                            ToolGovernanceState governance =
                                    runtimeContext == null
                                            ? null
                                            : runtimeContext.get(ToolGovernanceState.class);
                            ToolGovernanceDecision decision =
                                    governance == null
                                            ? ToolGovernanceDecision.allow(
                                                    ToolGovernanceState.fingerprint(
                                                            delegate.name(), param.getInput()))
                                            : governance.beforeInvocation(
                                                    toolCallId,
                                                    delegate.name(),
                                                    param.getInput());

                            long startedNanos = System.nanoTime();
                            try {
                                ToolResult result;
                                if (!decision.allowed()) {
                                    String previousCode =
                                            decision.blockedByFailureCode() == null
                                                    ? "NON_RETRYABLE_FAILURE"
                                                    : decision.blockedByFailureCode();
                                    result =
                                            ToolResult.failure(
                                                    "tool invocation blocked by governance: identical arguments already failed with "
                                                            + previousCode
                                                            + "; do not retry unchanged arguments",
                                                    ToolGovernanceState
                                                            .FAILURE_REPEATED_NON_RETRYABLE_CALL,
                                                    false);
                                } else {
                                    result = delegate.execute(context, param.getInput());
                                }

                                if (governance != null) {
                                    governance.recordResult(
                                            toolCallId,
                                            decision,
                                            result,
                                            elapsedMillis(startedNanos),
                                            telemetryBuffer.latestOutboundHttp());
                                }
                                return toToolResultBlock(param, result);
                            } catch (RuntimeException exception) {
                                if (governance != null) {
                                    governance.recordException(
                                            toolCallId,
                                            decision,
                                            elapsedMillis(startedNanos),
                                            telemetryBuffer.latestOutboundHttp());
                                }
                                throw exception;
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
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
