package com.agentforge.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "agentforge.knowledge.evaluation")
public class KnowledgeEvaluationProperties {

    private boolean enabled;
    private String indexName = "agentforge-knowledge-eval-v070";
    private String datasetResource = "classpath:knowledge/eval/v070-gold-dataset.json";
    private int topK = 5;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getIndexName() {
        return indexName;
    }

    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }

    public String getDatasetResource() {
        return datasetResource;
    }

    public void setDatasetResource(String datasetResource) {
        this.datasetResource = datasetResource;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public boolean isConfigured() {
        return enabled
                && StringUtils.hasText(indexName)
                && StringUtils.hasText(datasetResource)
                && topK >= 5
                && topK <= 20;
    }

    public void validate(KnowledgeHybridProperties hybridProperties) {
        if (!enabled) {
            return;
        }
        if (!StringUtils.hasText(indexName)) {
            throw new IllegalStateException("Knowledge evaluation index name is required.");
        }
        if (!indexName.matches("[a-z0-9][a-z0-9._-]{0,254}")) {
            throw new IllegalStateException("Knowledge evaluation index name is invalid.");
        }
        if (!StringUtils.hasText(datasetResource)) {
            throw new IllegalStateException("Knowledge evaluation dataset resource is required.");
        }
        if (topK < 5 || topK > 20) {
            throw new IllegalStateException("Knowledge evaluation topK must be between 5 and 20.");
        }
        if (hybridProperties == null || !hybridProperties.isConfigured()) {
            throw new IllegalStateException(
                    "Knowledge evaluation requires configured hybrid retrieval.");
        }
        String productionIndex =
                hybridProperties.getElasticsearch().getIndexName();
        if (indexName.equals(productionIndex)) {
            throw new IllegalStateException(
                    "Knowledge evaluation index must differ from the production knowledge index.");
        }
    }
}
