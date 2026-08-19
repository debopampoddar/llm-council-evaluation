package com.debopam.llmcouncil.evaluation.domain;

import java.util.List;

/** Versioned description of one evaluation experiment. */
public record EvaluationPlan(
        Integer version,
        String id,
        String description,
        String councilBaseUrl,
        String dataset,
        String rubric,
        String outputDirectory,
        Long seed,
        Integer repetitions,
        ExecutionSettings execution,
        List<ModelSpec> models,
        List<VariantSpec> variants,
        List<ComparisonSpec> comparisons,
        List<JudgeSpec> judges
) {
    public static final int SUPPORTED_VERSION = 1;

    public record ExecutionSettings(
            Integer maxCalls,
            Double maxEstimatedCostUsd,
            Integer councilRequestTimeoutSeconds,
            Integer judgeInvalidRetries,
            Boolean continueOnFailure,
            Boolean liveCallsAcknowledged,
            Boolean billableCallsAcknowledged
    ) {}

    public record ModelSpec(
            String id,
            String provider,
            String providerModelId,
            String modelFamily,
            String baseUrl,
            Integer maxOutputTokens,
            Integer contextWindowTokens,
            Double temperature,
            Integer timeoutSeconds,
            Integer retryMaxAttempts,
            Integer retryBaseDelayMs,
            Double costPer1kInputTokens,
            Double costPer1kOutputTokens
    ) {}

    public record VariantSpec(
            String id,
            String displayName,
            VariantType type,
            Boolean enabled,
            String profileId,
            String depthMode,
            String modelId,
            Integer samples
    ) {}

    public enum VariantType {
        DIRECT,
        COUNCIL,
        SAME_MODEL_ENSEMBLE
    }

    public record ComparisonSpec(String id, String left, String right, Boolean enabled) {}

    public record JudgeSpec(String id, String modelId, Boolean mirrored, Boolean enabled) {}
}
