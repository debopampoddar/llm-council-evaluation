package com.debopam.llmcouncil.evaluation.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/** Immutable evidence produced by one case/variant/repetition attempt. */
public record AnswerResult(
        String unitId,
        String caseId,
        String variantId,
        int repetition,
        AnswerStatus status,
        String answer,
        Instant startedAt,
        Instant completedAt,
        long durationMs,
        UsageMetrics usage,
        String failureCategory,
        String failureReason,
        List<String> warnings,
        List<String> componentAnswers,
        JsonNode councilResult
) {
    public enum AnswerStatus { COMPLETED, PARTIAL, FAILED, CANCELLED, INTERRUPTED }
}
