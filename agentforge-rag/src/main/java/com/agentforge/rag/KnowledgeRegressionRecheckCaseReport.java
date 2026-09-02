package com.agentforge.rag;

import java.util.List;

public record KnowledgeRegressionRecheckCaseReport(
        String caseName,
        int trials,
        boolean initialControlPassed,
        boolean initialChallengerPassed,
        int controlPasses,
        int challengerPasses,
        String status,
        List<KnowledgeEvaluationCaseResult> controlTrials,
        List<KnowledgeEvaluationCaseResult> challengerTrials) {

    public KnowledgeRegressionRecheckCaseReport {
        controlTrials =
                controlTrials == null ? List.of() : List.copyOf(controlTrials);
        challengerTrials =
                challengerTrials == null ? List.of() : List.copyOf(challengerTrials);
    }
}
