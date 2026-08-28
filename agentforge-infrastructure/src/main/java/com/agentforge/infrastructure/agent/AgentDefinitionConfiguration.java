package com.agentforge.infrastructure.agent;

import com.agentforge.agent.AgentDefinition;
import com.agentforge.agent.AgentDefinitionRepository;
import com.agentforge.agent.AgentDefinitionService;
import com.agentforge.infrastructure.config.AgentRuntimeProperties;
import com.agentforge.infrastructure.config.ModelProviderProperties;
import com.agentforge.tool.device.DeviceStatusTool;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentDefinitionConfiguration {

    @Bean
    public AgentDefinitionRepository agentDefinitionRepository(
            AgentRuntimeProperties agentProperties,
            ModelProviderProperties modelProperties) {
        InMemoryAgentDefinitionRepository repository = new InMemoryAgentDefinitionRepository();

        if (agentProperties.isEnabled()) {
            Instant now = Instant.now();
            repository.save(
                    new AgentDefinition(
                            "default",
                            agentProperties.getName(),
                            "V0.2 default Agent",
                            agentProperties.getSystemPrompt(),
                            "dashscope",
                            modelProperties.getDashscope().getModel(),
                            List.of(DeviceStatusTool.NAME),
                            true,
                            1L,
                            now,
                            now));
        }
        return repository;
    }

    @Bean
    public AgentDefinitionService agentDefinitionService(AgentDefinitionRepository repository) {
        return new AgentDefinitionService(repository);
    }
}
