package com.agentforge.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ModelProviderProperties.class, AgentRuntimeProperties.class})
public class AgentForgeInfrastructureConfiguration {}
