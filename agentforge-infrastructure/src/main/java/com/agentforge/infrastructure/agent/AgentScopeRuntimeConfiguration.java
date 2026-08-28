package com.agentforge.infrastructure.agent;

import com.agentforge.infrastructure.config.AgentRuntimeProperties;
import com.agentforge.infrastructure.model.DashScopeModelFactory;
import com.agentforge.runtime.AgentRuntime;
import com.agentforge.runtime.AgentScopeHarnessRuntime;
import io.agentscope.core.model.Model;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentScopeRuntimeConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "agentforge.agent.enabled", havingValue = "true")
    public AgentRuntime agentRuntime(AgentRuntimeProperties properties, DashScopeModelFactory modelFactory) {
        Model model = modelFactory.createStreamingModel();
        return new AgentScopeHarnessRuntime(properties.getName(), properties.getSystemPrompt(), model);
    }
}
