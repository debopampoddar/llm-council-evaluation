package com.debopam.llmcouncil.evaluation.domain;

import java.time.Instant;

/** Evidence that one judge can return valid, directionally correct structured output. */
public record JudgePreflightResult(
        String judgeId,
        String modelId,
        Status status,
        String rawResponse,
        String failureCategory,
        String failureReason,
        Instant completedAt,
        long durationMs,
        UsageMetrics usage
) {
    public enum Status { PASSED, FAILED }
}
