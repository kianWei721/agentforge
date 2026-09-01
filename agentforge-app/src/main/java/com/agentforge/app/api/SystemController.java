package com.agentforge.app.api;

import com.agentforge.agent.AgentDefinitionService;
import com.agentforge.core.AgentForgeConstants;
import com.agentforge.infrastructure.config.AgentRuntimeProperties;
import com.agentforge.infrastructure.config.AgentSessionCacheProperties;
import com.agentforge.infrastructure.config.IoTDeviceStatusProperties;
import com.agentforge.infrastructure.config.IoTLureLampControlProperties;
import com.agentforge.infrastructure.config.KnowledgeHybridProperties;
import com.agentforge.infrastructure.config.ToolConfirmationProperties;
import com.agentforge.infrastructure.config.ToolGovernanceProperties;
import com.agentforge.infrastructure.config.ToolPolicyProperties;
import com.agentforge.infrastructure.health.InfrastructureStatus;
import com.agentforge.infrastructure.health.InfrastructureStatusService;
import com.agentforge.model.ModelGatewayCatalog;
import com.agentforge.rag.KnowledgeIngestionService;
import com.agentforge.rag.KnowledgeRetriever;
import com.agentforge.runtime.AgentRuntimeManager;
import com.agentforge.tool.ToolRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping(AgentForgeConstants.API_PREFIX + "/system")
public class SystemController {

    private final ModelGatewayCatalog modelGatewayCatalog;
    private final AgentRuntimeProperties agentProperties;
    private final AgentDefinitionService agentService;
    private final ToolRegistry toolRegistry;
    private final AgentRuntimeManager runtimeManager;
    private final InfrastructureStatusService infrastructureStatusService;
    private final AgentSessionCacheProperties sessionCacheProperties;
    private final IoTDeviceStatusProperties iotDeviceStatusProperties;
    private final IoTLureLampControlProperties iotLureLampControlProperties;
    private final ToolGovernanceProperties toolGovernanceProperties;
    private final ToolPolicyProperties toolPolicyProperties;
    private final ToolConfirmationProperties toolConfirmationProperties;
    private final KnowledgeRetriever knowledgeRetriever;
    private final KnowledgeIngestionService knowledgeIngestionService;
    private final KnowledgeHybridProperties knowledgeHybridProperties;

    public SystemController(
            ModelGatewayCatalog modelGatewayCatalog,
            AgentRuntimeProperties agentProperties,
            AgentDefinitionService agentService,
            ToolRegistry toolRegistry,
            AgentRuntimeManager runtimeManager,
            InfrastructureStatusService infrastructureStatusService,
            AgentSessionCacheProperties sessionCacheProperties,
            IoTDeviceStatusProperties iotDeviceStatusProperties,
            IoTLureLampControlProperties iotLureLampControlProperties,
            ToolGovernanceProperties toolGovernanceProperties,
            ToolPolicyProperties toolPolicyProperties,
            ToolConfirmationProperties toolConfirmationProperties,
            KnowledgeRetriever knowledgeRetriever,
            KnowledgeIngestionService knowledgeIngestionService,
            KnowledgeHybridProperties knowledgeHybridProperties) {
        this.modelGatewayCatalog = modelGatewayCatalog;
        this.agentProperties = agentProperties;
        this.agentService = agentService;
        this.toolRegistry = toolRegistry;
        this.runtimeManager = runtimeManager;
        this.infrastructureStatusService = infrastructureStatusService;
        this.sessionCacheProperties = sessionCacheProperties;
        this.iotDeviceStatusProperties = iotDeviceStatusProperties;
        this.iotLureLampControlProperties = iotLureLampControlProperties;
        this.toolGovernanceProperties = toolGovernanceProperties;
        this.toolPolicyProperties = toolPolicyProperties;
        this.toolConfirmationProperties = toolConfirmationProperties;
        this.knowledgeRetriever = knowledgeRetriever;
        this.knowledgeIngestionService = knowledgeIngestionService;
        this.knowledgeHybridProperties = knowledgeHybridProperties;
    }

    @GetMapping("/health")
    public Mono<Map<String, Object>> health() {
        return Mono.fromCallable(this::buildHealth).subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> buildHealth() {
        InfrastructureStatus infrastructure = infrastructureStatusService.check();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", "AgentForge");
        result.put("version", "0.3.0-SNAPSHOT");
        result.put("status", infrastructure.ready() ? "UP" : "DEGRADED");
        result.put("milestone", "V0.6.3-retrieval-quality-gate");
        result.put("mysql", infrastructure.mysqlUp() ? "UP" : "DOWN");
        result.put("redis", infrastructure.redisUp() ? "UP" : "DOWN");
        result.put("activeModelProvider", modelGatewayCatalog.activeProvider());
        result.put("registeredModelProviders", modelGatewayCatalog.registeredProviders());
        result.put("agentRuntimeEnabled", agentProperties.isEnabled());
        result.put("agentDefinitions", agentService.list().size());
        result.put("registeredTools", toolRegistry.descriptors().size());
        result.put("cachedAgentRuntimes", runtimeManager.cachedRuntimeCount());
        result.put("sessionCacheEnabled", sessionCacheProperties.isEnabled());
        result.put("sessionCacheTtl", sessionCacheProperties.getTtl().toString());
        result.put(
                "contextRestoreMaxMessages",
                sessionCacheProperties.getRestoreMaxMessages());
        result.put(
                "iotDeviceStatusAdapter",
                iotDeviceStatusProperties.getMode().name().toLowerCase(java.util.Locale.ROOT));
        result.put(
                "iotDeviceStatusConfigured",
                iotDeviceStatusProperties.isConfigured());
        result.put(
                "iotDeviceStatusRequestTimeout",
                iotDeviceStatusProperties.getRequestTimeout().toString());
        result.put(
                "iotLureLampControlEnabled",
                iotLureLampControlProperties.isEnabled());
        result.put(
                "iotLureLampControlVerifyAttempts",
                iotLureLampControlProperties.getVerifyAttempts());
        result.put("toolGovernanceEnabled", toolGovernanceProperties.isEnabled());
        result.put(
                "blockRepeatedNonRetryableToolCalls",
                toolGovernanceProperties.isBlockRepeatedNonRetryableCalls());
        result.put(
                "toolGovernanceMaxRecordedInvocations",
                toolGovernanceProperties.getMaxRecordedInvocations());
        result.put("toolPolicyEnabled", toolPolicyProperties.isEnabled());
        result.put(
                "toolPolicyConfirmWriteTools",
                toolPolicyProperties.isRequireConfirmationForWriteTools());
        result.put(
                "toolPolicyConfirmHighRiskReadOnly",
                toolPolicyProperties.isRequireConfirmationForHighRiskReadOnly());
        result.put("toolConfirmationTtl", toolConfirmationProperties.getTtl().toString());
        result.put("knowledgeGroundingEnabled", true);
        result.put("knowledgeRetrieverMode", knowledgeRetriever.mode());
        result.put("knowledgeDocuments", knowledgeIngestionService.list().size());
        result.put("knowledgeHybridEnabled", knowledgeHybridProperties.isEnabled());
        result.put("knowledgeHybridConfigured", knowledgeHybridProperties.isConfigured());
        result.put(
                "knowledgeEmbeddingModel",
                knowledgeHybridProperties.getEmbedding().getModel());
        result.put(
                "knowledgeEmbeddingDimensions",
                knowledgeHybridProperties.getEmbedding().getDimensions());
        result.put(
                "knowledgeElasticsearchIndex",
                knowledgeHybridProperties.getElasticsearch().getIndexName());
        result.put(
                "knowledgeRelevanceGateEnabled",
                knowledgeHybridProperties.isRelevanceGateEnabled());
        result.put(
                "knowledgeMinVectorSimilarity",
                knowledgeHybridProperties.getMinVectorSimilarity());
        return result;
    }
}
