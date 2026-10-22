package com.agentforge.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
    ModelProviderProperties.class,
    AgentRuntimeProperties.class,
    AgentSessionCacheProperties.class,
    IoTDeviceStatusProperties.class,
    IoTLureLampControlProperties.class,
    ToolGovernanceProperties.class,
    ToolPolicyProperties.class,
    ToolConfirmationProperties.class,
    KnowledgeHybridProperties.class,
    KnowledgeQueryRewriteProperties.class,
    KnowledgeRerankProperties.class,
    KnowledgeAnswerabilityProperties.class,
    KnowledgeEvaluationProperties.class,
    RetrievalBudgetProperties.class,
    PrincipalDirectoryProperties.class,
    ModelGatewayProperties.class
})
public class AgentForgeInfrastructureConfiguration {}
