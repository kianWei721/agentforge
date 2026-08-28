package com.agentforge.runtime;

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
import reactor.core.publisher.Mono;

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
                            ToolInvocationContext context =
                                    new ToolInvocationContext(
                                            agentId,
                                            runtimeContext != null ? runtimeContext.getUserId() : null,
                                            runtimeContext != null ? runtimeContext.getSessionId() : null);

                            ToolResult result = delegate.execute(context, param.getInput());
                            return ToolResultBlock.builder()
                                    .id(param.getToolUseBlock().getId())
                                    .name(delegate.name())
                                    .output(
                                            List.of(
                                                    TextBlock.builder()
                                                            .text(result.toModelText())
                                                            .build()))
                                    .state(
                                            result.success()
                                                    ? ToolResultState.SUCCESS
                                                    : ToolResultState.ERROR)
                                    .build();
                        });
    }
}
