package com.agentforge.infrastructure.agent;

import com.agentforge.agent.AgentDefinition;
import com.agentforge.agent.AgentDefinitionRepository;
import com.agentforge.conversation.ConversationService;
import com.agentforge.infrastructure.config.AgentRuntimeProperties;
import com.agentforge.infrastructure.config.ModelGatewayProperties;
import com.agentforge.infrastructure.config.RetrievalBudgetProperties;
import com.agentforge.infrastructure.config.ToolGovernanceProperties;
import com.agentforge.infrastructure.config.ToolPolicyProperties;
import com.agentforge.infrastructure.gateway.ModelGatewayClient;
import com.agentforge.infrastructure.model.DashScopeModelFactory;
import com.agentforge.infrastructure.model.GatewayChatModel;
import com.agentforge.observability.execution.ExecutionService;
import com.agentforge.runtime.AgentConversationRuntimeService;
import com.agentforge.runtime.AgentRuntime;
import com.agentforge.runtime.AgentRuntimeFactory;
import com.agentforge.runtime.AgentRuntimeManager;
import com.agentforge.runtime.AgentScopeHarnessRuntime;
import com.agentforge.runtime.BaselineToolExecutionPolicy;
import com.agentforge.runtime.HarnessMemoryOptions;
import com.agentforge.runtime.RetrievalBudgetOptions;
import com.agentforge.runtime.SessionContextRestoreService;
import com.agentforge.runtime.ToolGovernanceOptions;
import com.agentforge.runtime.ToolExecutionPolicy;
import com.agentforge.runtime.ToolPolicyOptions;
import com.agentforge.runtime.confirmation.ToolConfirmationService;
import com.agentforge.tool.AgentTool;
import com.agentforge.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
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
            RetrievalBudgetProperties retrievalBudgetProperties,
            ToolConfirmationService confirmationService,
            AgentRuntimeProperties agentProperties,
            ModelGatewayProperties gatewayProperties,
            ObjectProvider<ModelGatewayClient> gatewayProvider) {
        HarnessMemoryOptions memoryOptions = agentProperties.getMemory().toOptions();
        governanceProperties.validate();
        retrievalBudgetProperties.validate();
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
        RetrievalBudgetOptions retrievalBudgetOptions =
                new RetrievalBudgetOptions(
                        retrievalBudgetProperties.isEnabled(),
                        retrievalBudgetProperties.getMaxCallsPerExecution());
        return new AgentRuntimeFactory() {
            @Override
            public AgentRuntime create(AgentDefinition definition, List<AgentTool> tools) {
                Model model = model(definition, modelFactory, gatewayProperties, gatewayProvider);
                return new AgentScopeHarnessRuntime(
                        definition.id(),
                        definition.name(),
                        definition.systemPrompt(),
                        model,
                        tools,
                        stateStore,
                        governanceOptions,
                        executionPolicy,
                        confirmationService,
                        retrievalBudgetOptions,
                        memoryOptions);
            }
        };
    }

    /**
     * dashscope calls DashScope directly; gateway calls the model gateway route named by the agent's
     * model name. An agent set to the gateway while the gateway is off fails rather than quietly
     * calling the vendor.
     */
    static Model model(
            AgentDefinition definition,
            DashScopeModelFactory modelFactory,
            ModelGatewayProperties gatewayProperties,
            ObjectProvider<ModelGatewayClient> gatewayProvider) {
        String provider = definition.modelProvider() == null ? "" : definition.modelProvider().trim();
        if ("dashscope".equalsIgnoreCase(provider)) {
            return modelFactory.createStreamingModel(definition.modelName());
        }
        if ("gateway".equalsIgnoreCase(provider)) {
            ModelGatewayClient gateway = gatewayProvider.getIfAvailable();
            if (gateway == null) {
                throw new IllegalStateException(
                        "Agent '" + definition.id() + "' uses modelProvider=gateway, which requires"
                                + " agentforge.model-gateway.enabled=true.");
            }
            return new GatewayChatModel(
                    gateway,
                    definition.modelName(),
                    gatewayProperties.getAgentMaxTokens(),
                    gatewayProperties.getAgentRequestTimeout(),
                    new ObjectMapper());
        }
        throw new IllegalArgumentException(
                "AgentScope runtime supports modelProvider=dashscope or gateway. Requested: " + provider);
    }

    @Bean(destroyMethod = "close")
    public AgentRuntimeManager agentRuntimeManager(
            AgentDefinitionRepository agentRepository,
            ToolRegistry toolRegistry,
            AgentRuntimeFactory runtimeFactory,
            AgentRuntimeProperties agentProperties) {
        return new AgentRuntimeManager(
                agentRepository,
                toolRegistry,
                runtimeFactory,
                agentProperties.getRuntimeGenerationTurns());
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
