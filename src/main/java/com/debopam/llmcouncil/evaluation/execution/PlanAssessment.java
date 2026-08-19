package com.debopam.llmcouncil.evaluation.execution;

import java.util.List;

/** Read-only preflight and conservative workload estimate. */
public record PlanAssessment(
        int cases,
        int repetitions,
        long answerUnits,
        long minimumAnswerCalls,
        long maximumAnswerCalls,
        long maximumJudgeCalls,
        long maximumTotalCalls,
        boolean billableProviders,
        List<VariantEstimate> variants,
        List<String> warnings
) {
    public record VariantEstimate(String variantId, long units, int minimumCallsPerUnit,
                                  int maximumCallsPerUnit, String detail) {}
}
