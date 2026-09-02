package com.agentforge.infrastructure.config;

import java.util.HashSet;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "agentforge.knowledge.evaluation")
public class KnowledgeEvaluationProperties {

    private boolean enabled;
    private String indexName = "agentforge-knowledge-eval-v070";
    private String datasetResource = "classpath:knowledge/eval/v070-gold-dataset-v2.json";
    private int topK = 5;
    // Sweep spans materially different rank-decay regimes for the current
    // candidate window (20): head-heavy (5), window-scale (20), literature
    // baseline (60), and near-flat stress point (120).
    private List<Integer> rrfKValues = List.of(5, 20, 60, 120);

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

    public List<Integer> getRrfKValues() {
        return rrfKValues;
    }

    public void setRrfKValues(List<Integer> rrfKValues) {
        this.rrfKValues =
                rrfKValues == null ? List.of() : List.copyOf(rrfKValues);
    }

    public boolean isConfigured() {
        return enabled
                && StringUtils.hasText(indexName)
                && StringUtils.hasText(datasetResource)
                && topK >= 5
                && topK <= 20
                && !rrfKValues.isEmpty();
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
        if (rrfKValues == null || rrfKValues.size() < 3 || rrfKValues.size() > 8) {
            throw new IllegalStateException(
                    "Knowledge evaluation rrfKValues must contain between 3 and 8 values.");
        }
        if (new HashSet<>(rrfKValues).size() != rrfKValues.size()) {
            throw new IllegalStateException(
                    "Knowledge evaluation rrfKValues must be unique.");
        }
        for (Integer value : rrfKValues) {
            if (value == null || value < 1 || value > 1000) {
                throw new IllegalStateException(
                        "Knowledge evaluation rrfKValues must be between 1 and 1000.");
            }
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
