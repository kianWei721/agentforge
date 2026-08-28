package com.agentforge.infrastructure.agent;

import com.agentforge.agent.AgentDefinition;
import com.agentforge.agent.AgentDefinitionRepository;
import com.agentforge.agent.AgentDefinitionService;
import com.agentforge.infrastructure.config.AgentRuntimeProperties;
import com.agentforge.infrastructure.config.ModelProviderProperties;
import com.agentforge.tool.device.DeviceStatusTool;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class AgentDefinitionConfiguration {

    @Bean
    public AgentDefinitionRepository agentDefinitionRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcAgentDefinitionRepository(jdbcTemplate);
    }

    @Bean
    public AgentDefinitionService agentDefinitionService(AgentDefinitionRepository repository) {
        return new AgentDefinitionService(repository);
    }

    @Bean
    public ApplicationRunner defaultAgentSeeder(
            AgentDefinitionRepository repository,
            AgentRuntimeProperties agentProperties,
            ModelProviderProperties modelProperties) {
        return args -> {
            if (!agentProperties.isEnabled() || repository.existsById("default")) {
                return;
            }

            Instant now = Instant.now();
            repository.save(
                    new AgentDefinition(
                            "default",
                            agentProperties.getName(),
                            "V0.3 default Agent",
                            agentProperties.getSystemPrompt(),
                            "dashscope",
                            modelProperties.getDashscope().getModel(),
                            List.of(DeviceStatusTool.NAME),
                            true,
                            1L,
                            now,
                            now));
        };
    }
}
