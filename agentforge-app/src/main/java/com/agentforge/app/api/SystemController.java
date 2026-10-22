package com.agentforge.app.api;

import com.agentforge.agent.AgentDefinitionService;
import com.agentforge.core.AgentForgeConstants;
import com.agentforge.infrastructure.config.AgentRuntimeProperties;
import com.agentforge.infrastructure.config.AgentSessionCacheProperties;
import com.agentforge.infrastructure.config.IoTDeviceStatusProperties;
import com.agentforge.infrastructure.config.IoTLureLampControlProperties;
import com.agentforge.infrastructure.config.KnowledgeAnswerabilityProperties;
import com.agentforge.infrastructure.config.KnowledgeEvaluationProperties;
import com.agentforge.infrastructure.config.KnowledgeHybridProperties;
import com.agentforge.infrastructure.config.KnowledgeQueryRewriteProperties;
import com.agentforge.infrastructure.config.KnowledgeRerankProperties;
import com.agentforge.infrastructure.config.ModelGatewayProperties;
import com.agentforge.infrastructure.config.RetrievalBudgetProperties;
import com.agentforge.infrastructure.config.ToolConfirmationProperties;
import com.agentforge.infrastructure.config.ToolGovernanceProperties;
import com.agentforge.infrastructure.config.ToolPolicyProperties;
import com.agentforge.infrastructure.health.InfrastructureStatus;
import com.agentforge.infrastructure.health.InfrastructureStatusService;
import com.agentforge.model.ModelGatewayCatalog;
import com.agentforge.rag.KnowledgeChannelStrategyAnalyzer;
import com.agentforge.rag.KnowledgeIngestionService;
import com.agentforge.rag.KnowledgeRetriever;
import com.agentforge.runtime.AgentRuntimeManager;
import com.agentforge.tool.ToolRegistry;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final KnowledgeQueryRewriteProperties knowledgeQueryRewriteProperties;
    private final KnowledgeRerankProperties knowledgeRerankProperties;
    private final KnowledgeAnswerabilityProperties knowledgeAnswerabilityProperties;
    private final KnowledgeEvaluationProperties knowledgeEvaluationProperties;
    private final RetrievalBudgetProperties retrievalBudgetProperties;
    private final ModelGatewayProperties modelGatewayProperties;

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
            KnowledgeHybridProperties knowledgeHybridProperties,
            KnowledgeQueryRewriteProperties knowledgeQueryRewriteProperties,
            KnowledgeRerankProperties knowledgeRerankProperties,
            KnowledgeAnswerabilityProperties knowledgeAnswerabilityProperties,
            KnowledgeEvaluationProperties knowledgeEvaluationProperties,
            RetrievalBudgetProperties retrievalBudgetProperties,
            ModelGatewayProperties modelGatewayProperties) {
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
        this.knowledgeQueryRewriteProperties = knowledgeQueryRewriteProperties;
        this.knowledgeRerankProperties = knowledgeRerankProperties;
        this.knowledgeAnswerabilityProperties = knowledgeAnswerabilityProperties;
        this.knowledgeEvaluationProperties = knowledgeEvaluationProperties;
        this.retrievalBudgetProperties = retrievalBudgetProperties;
        this.modelGatewayProperties = modelGatewayProperties;
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
        result.put("milestone", "V0.7-slice1b4-production-promotion");
        result.put("mysql", infrastructure.mysqlUp() ? "UP" : "DOWN");
        result.put("redis", infrastructure.redisUp() ? "UP" : "DOWN");
        result.put("activeModelProvider", modelGatewayCatalog.activeProvider());
        result.put("registeredModelProviders", modelGatewayCatalog.registeredProviders());
        // When enabled, the knowledge*Model values below are ignored: rewrite and answerability
        // use the gateway's RAG route.
        result.put("modelGatewayEnabled", modelGatewayProperties.isEnabled());
        if (modelGatewayProperties.isEnabled()) {
            result.put("modelGatewayRagRoute", modelGatewayProperties.getRagRoute());
            result.put("modelGatewayChatRoute", modelGatewayProperties.getChatRoute());
        }
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
        result.put("knowledgeDocuments", knowledgeIngestionService.count());
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
        result.put(
                "knowledgeQueryRewriteEnabled",
                knowledgeQueryRewriteProperties.isEnabled());
        result.put(
                "knowledgeQueryRewriteConfigured",
                knowledgeQueryRewriteProperties.isConfigured());
        result.put(
                "knowledgeQueryRewriteModel",
                knowledgeQueryRewriteProperties.getModel());
        result.put(
                "knowledgeQueryRewriteMaxQueryChars",
                knowledgeQueryRewriteProperties.getMaxQueryChars());
        result.put("knowledgeRerankEnabled", knowledgeRerankProperties.isEnabled());
        result.put("knowledgeRerankConfigured", knowledgeRerankProperties.isConfigured());
        result.put("knowledgeRerankModel", knowledgeRerankProperties.getModel());
        result.put(
                "knowledgeRerankCandidateLimit",
                knowledgeRerankProperties.getCandidateLimit());
        result.put(
                "knowledgeAnswerabilityEnabled",
                knowledgeAnswerabilityProperties.isEnabled());
        result.put(
                "knowledgeAnswerabilityConfigured",
                knowledgeAnswerabilityProperties.isConfigured());
        result.put(
                "knowledgeAnswerabilityModel",
                knowledgeAnswerabilityProperties.getModel());
        result.put(
                "knowledgeAnswerabilityCandidateLimit",
                knowledgeAnswerabilityProperties.getCandidateLimit());
        result.put("knowledgeEvaluationEnabled", knowledgeEvaluationProperties.isEnabled());
        result.put(
                "knowledgeEvaluationConfigured",
                knowledgeEvaluationProperties.isConfigured()
                        && knowledgeHybridProperties.isConfigured()
                        && knowledgeQueryRewriteProperties.isConfigured()
                        && knowledgeRerankProperties.isConfigured()
                        && knowledgeAnswerabilityProperties.isConfigured());
        result.put(
                "knowledgeEvaluationIndex",
                knowledgeEvaluationProperties.getIndexName());
        result.put(
                "knowledgeEvaluationDataset",
                knowledgeEvaluationProperties.getDatasetResource());
        result.put(
                "knowledgeEvaluationTopK",
                knowledgeEvaluationProperties.getTopK());
        result.put(
                "knowledgeEvaluationRrfKValues",
                knowledgeEvaluationProperties.getRrfKValues());
        result.put(
                "knowledgeProductionRrfK",
                knowledgeHybridProperties.getRrfK());
        String productionQueryStrategy =
                knowledgeQueryRewriteProperties.isEnabled()
                        ? "REWRITE"
                        : "ORIGINAL";
        result.put(
                "knowledgeProductionQueryStrategy",
                productionQueryStrategy);
        result.put(
                "knowledgeProductionBaseline",
                productionQueryStrategy
                        + "+RRF_K"
                        + knowledgeHybridProperties.getRrfK());
        String promotionState;
        if (!knowledgeQueryRewriteProperties.isEnabled()
                && knowledgeHybridProperties.getRrfK() == 5) {
            promotionState = "PROMOTED_ORIGINAL_K5";
        } else if (knowledgeQueryRewriteProperties.isEnabled()
                && knowledgeHybridProperties.getRrfK() == 60) {
            promotionState = "LEGACY_CONTROL_REWRITE_K60";
        } else {
            promotionState = "CUSTOM";
        }
        result.put(
                "knowledgeProductionPromotionState",
                promotionState);
        result.put(
                "knowledgeProductionPromotionTarget",
                "ORIGINAL+RRF_K5");
        result.put(
                "knowledgeEvaluationChannelStrategies",
                List.of(
                        "ORIGINAL",
                        "REWRITE",
                        "BM25_REWRITE_VECTOR_ORIGINAL",
                        "BM25_ORIGINAL_VECTOR_REWRITE"));
        result.put(
                "knowledgeEvaluationSelectionPolicy",
                KnowledgeChannelStrategyAnalyzer.SELECTION_POLICY);
        result.put(
                "knowledgeEvaluationDecisionBoundary",
                KnowledgeChannelStrategyAnalyzer.DECISION_BOUNDARY);
        result.put(
                "knowledgeEndToEndPromotionGate",
                "EVALUATION_PROMOTION_GATE_MANUAL_PRODUCTION_CHANGE_REQUIRED");
        result.put(
                "knowledgeEndToEndControl",
                "LEGACY_REWRITE+RRF_K60");
        result.put(
                "knowledgeEndToEndChallenger",
                "PROMOTION_TARGET_ORIGINAL+RRF_K5");
        result.put(
                "knowledgeEndToEndRegressionRecheckTrials",
                3);
        result.put(
                "knowledgeEndToEndRegressionRecheckPolicy",
                "TARGET_INITIAL_NEW_FAILURES_ONLY");
        result.put(
                "knowledgeEndToEndRegressionClearRule",
                "CONTROL_3_OF_3_PASS_AND_CHALLENGER_3_OF_3_PASS");
        result.put(
                "knowledgeEvaluationCandidateWindow",
                Math.min(
                        100,
                        Math.max(
                                20,
                                knowledgeEvaluationProperties.getTopK()
                                        * knowledgeHybridProperties.getCandidateMultiplier())));
        result.put(
                "knowledgeEvaluationElasticsearchConnectTimeoutFloor",
                "PT5S");
        result.put(
                "knowledgeEvaluationElasticsearchRequestTimeoutFloor",
                "PT15S");
        result.put(
                "knowledgeEvaluationElasticsearchSearchAttempts",
                2);
        result.put("retrievalBudgetEnabled", retrievalBudgetProperties.isEnabled());
        result.put(
                "retrievalBudgetMaxCallsPerExecution",
                retrievalBudgetProperties.getMaxCallsPerExecution());
        return result;
    }
}
