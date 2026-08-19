package com.debopam.llmcouncil.evaluation.domain;

/** Provider usage for one evaluated answer or one judge call. */
public record UsageMetrics(
        int calls,
        long promptTokens,
        long completionTokens,
        Double estimatedCostUsd,
        boolean estimated,
        boolean partiallyPriced
) {
    public static UsageMetrics empty() {
        return new UsageMetrics(0, 0, 0, null, true, false);
    }

    public UsageMetrics plus(UsageMetrics other) {
        if (calls == 0) return other;
        if (other.calls == 0) return this;
        Double cost = estimatedCostUsd == null && other.estimatedCostUsd == null
                ? null
                : value(estimatedCostUsd) + value(other.estimatedCostUsd);
        boolean partial = partiallyPriced || other.partiallyPriced
                || (estimatedCostUsd == null) != (other.estimatedCostUsd == null);
        return new UsageMetrics(calls + other.calls,
                promptTokens + other.promptTokens,
                completionTokens + other.completionTokens,
                cost, estimated || other.estimated, partial);
    }

    public long totalTokens() {
        return promptTokens + completionTokens;
    }

    private static double value(Double value) {
        return value == null ? 0.0 : value;
    }
}
