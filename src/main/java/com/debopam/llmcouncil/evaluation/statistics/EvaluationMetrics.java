package com.debopam.llmcouncil.evaluation.statistics;

import java.util.List;

public record EvaluationMetrics(
        int datasetCases,
        int repetitions,
        List<VariantMetric> variants,
        List<ComparisonMetric> comparisons,
        List<CheckMetric> deterministicChecks,
        int rawJudgments,
        int invalidRawJudgments,
        JudgeMetric judgeUsage,
        Double totalEstimatedCostUsd,
        boolean totalCostIncomplete
) {
    public record VariantMetric(String variantId, int attempts, int completed, int partial, int failed,
                                double answerRate, double averageDurationMs, double averageCalls,
                                double averageTokens, Double totalEstimatedCostUsd,
                                boolean costIncomplete) {}
    public record ComparisonMetric(String comparisonId, String leftVariant, String rightVariant,
                                   int intendedPairs, int eligiblePairs, int judgedPairs,
                                   int leftWins, int rightWins, int ties, int unresolved,
                                   int positionUnstable, int judgeDisagreement, int invalid, int missing,
                                   Double tieAdjustedLeftPreference, Double lower95, Double upper95,
                                   int intervalCases, Double unresolvedLowerBound,
                                   Double unresolvedUpperBound) {}
    public record CheckMetric(String variantId, int passed, int failed, int errors) {}
    public record JudgeMetric(int records, int calls, long tokens, double averageDurationMs,
                              Double totalEstimatedCostUsd, boolean costIncomplete) {}
}
