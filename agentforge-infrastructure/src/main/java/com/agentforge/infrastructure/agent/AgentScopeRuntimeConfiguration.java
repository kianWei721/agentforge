package com.agentforge.infrastructure.agent;

import com.agentforge.agent.AgentDefinition;
import com.agentforge.agent.AgentDefinitionRepository;
import com.agentforge.conversation.ConversationService;
import com.agentforge.infrastructure.model.DashScopeModelFactory;
import com.agentforge.observability.execution.ExecutionService;
import com.agentforge.runtime.AgentConversationRuntimeService;
import com.agentforge.runtime.AgentRuntime;
import com.agentforge.runtime.AgentRuntimeFactory;
import com.agentforge.runtime.AgentRuntimeManager;
import com.agentforge.runtime.AgentScopeHarnessRuntime;
import com.agentforge.tool.AgentTool;
import com.agentforge.tool.ToolRegistry;
import io.agentscope.core.model.Model;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentScopeRuntimeConfiguration {

    @Bean
    public AgentRuntimeFactory agentRuntimeFactory(DashScopeModelFactory modelFactory) {
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
                        tools);
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
            ExecutionService executionService) {
        return new AgentConversationRuntimeService(
                agentRepository, conversationService, runtimeManager, executionService);
    }
}
