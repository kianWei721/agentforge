package com.agentforge.infrastructure.session;

import com.agentforge.conversation.ConversationService;
import com.agentforge.infrastructure.config.AgentSessionCacheProperties;
import com.agentforge.runtime.SessionContextRestoreService;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class AgentSessionStateConfiguration {

    @Bean(destroyMethod = "close")
    public AgentStateStore agentStateStore(
            StringRedisTemplate redisTemplate,
            AgentSessionCacheProperties properties) {
        properties.validate();

        if (!properties.isEnabled()) {
            return new InMemoryAgentStateStore();
        }

        AgentStateStore redisStore =
                new SpringRedisAgentStateStore(
                        redisTemplate,
                        properties.getKeyPrefix(),
                        properties.getTtl());
        return new ResilientAgentStateStore(
                redisStore,
                new InMemoryAgentStateStore());
    }

    @Bean
    public SessionContextRestoreService sessionContextRestoreService(
            ConversationService conversationService,
            AgentStateStore stateStore,
            AgentSessionCacheProperties properties) {
        return new SessionContextRestoreService(
                conversationService,
                stateStore,
                properties.getRestoreMaxMessages());
    }
}
