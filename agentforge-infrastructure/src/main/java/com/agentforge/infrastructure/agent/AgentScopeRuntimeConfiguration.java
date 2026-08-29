package com.agentforge.infrastructure.agent;

import com.agentforge.agent.AgentDefinition;
import com.agentforge.agent.AgentDefinitionRepository;
import com.agentforge.conversation.ConversationService;
import com.agentforge.infrastructure.config.ToolGovernanceProperties;
import com.agentforge.infrastructure.config.ToolPolicyProperties;
import com.agentforge.infrastructure.model.DashScopeModelFactory;
import com.agentforge.observability.execution.ExecutionService;
import com.agentforge.runtime.AgentConversationRuntimeService;
import com.agentforge.runtime.AgentRuntime;
import com.agentforge.runtime.AgentRuntimeFactory;
import com.agentforge.runtime.AgentRuntimeManager;
import com.agentforge.runtime.AgentScopeHarnessRuntime;
import com.agentforge.runtime.BaselineToolExecutionPolicy;
import com.agentforge.runtime.SessionContextRestoreService;
import com.agentforge.runtime.ToolGovernanceOptions;
import com.agentforge.runtime.ToolExecutionPolicy;
import com.agentforge.runtime.ToolPolicyOptions;
import com.agentforge.runtime.confirmation.ToolConfirmationService;
import com.agentforge.tool.AgentTool;
import com.agentforge.tool.ToolRegistry;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentScopeRuntimeConfiguration {

    @Bean
    public AgentRuntimeFactory agentRuntimeFactory(
            DashScopeModelFactory modelFactory,
            AgentStateStore stateStore,
            ToolGovernanceProperties governanceProperties,
            ToolPolicyProperties policyProperties,
            ToolConfirmationService confirmationService) {
        governanceProperties.validate();
        ToolGovernanceOptions governanceOptions =
                new ToolGovernanceOptions(
                        governanceProperties.isEnabled(),
                        governanceProperties.isBlockRepeatedNonRetryableCalls(),
                        governanceProperties.getMaxRecordedInvocations());
        ToolExecutionPolicy executionPolicy =
                new BaselineToolExecutionPolicy(
                        new ToolPolicyOptions(
                                policyProperties.isEnabled(),
                                policyProperties.isRequireConfirmationForWriteTools(),
                                policyProperties.isRequireConfirmationForHighRiskReadOnly()));
        return new AgentRuntimeFactory() {
            @Override
            public AgentRuntime create(AgentDefinition definition, List<AgentTool> tools) {
                if (!"dashscope".equalsIgnoreCase(definition.modelProvider())) {
                    throw new IllegalArgumentException(
                            "AgentScope runtime currently supports modelProvider=dashscope only. Requested: "
                                    + definition.modelProvider());
                }

                Model model = modelFactory.createStreamingModel(definition.modelName());
                return new AgentScopeHarnessRuntime(
                        definition.id(),
                        definition.name(),
                        definition.systemPrompt(),
                        model,
                        tools,
                        stateStore,
                        governanceOptions,
                        executionPolicy,
                        confirmationService);
            }
        };
    }

    @Bean(destroyMethod = "close")
    public AgentRuntimeManager agentRuntimeManager(
            AgentDefinitionRepository agentRepository,
            ToolRegistry toolRegistry,
            AgentRuntimeFactory runtimeFactory) {
        return new AgentRuntimeManager(agentRepository, toolRegistry, runtimeFactory);
    }

    @Bean
    public AgentConversationRuntimeService agentConversationRuntimeService(
            AgentDefinitionRepository agentRepository,
            ConversationService conversationService,
            AgentRuntimeManager runtimeManager,
            ExecutionService executionService,
            SessionContextRestoreService contextRestoreService) {
        return new AgentConversationRuntimeService(
                agentRepository,
                conversationService,
                runtimeManager,
                executionService,
                contextRestoreService);
    }
}
