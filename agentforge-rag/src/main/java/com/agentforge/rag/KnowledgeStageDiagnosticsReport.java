package com.agentforge.rag;

import java.util.List;

public record KnowledgeStageDiagnosticsReport(
        int productionRrfK,
        int candidateWindow,
        List<String> selectedCases,
        List<KnowledgeEvaluationStageDiagnostic> diagnostics) {

    public KnowledgeStageDiagnosticsReport {
        selectedCases = selectedCases == null ? List.of() : List.copyOf(selectedCases);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
