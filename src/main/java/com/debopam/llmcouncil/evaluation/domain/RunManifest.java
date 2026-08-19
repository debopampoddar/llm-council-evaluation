package com.debopam.llmcouncil.evaluation.domain;

import java.time.Instant;

/** Immutable snapshot proving exactly which inputs and environment produced a run. */
public record RunManifest(
        int version,
        String runId,
        Instant createdAt,
        String planSha256,
        String datasetSha256,
        String rubricSha256,
        String councilCatalogSha256,
        long councilCatalogGeneration,
        String gitCommit,
        boolean gitDirty,
        String applicationVersion,
        String directPromptVersion,
        String ensemblePromptVersion,
        String judgePromptVersion,
        String javaVersion,
        String operatingSystem,
        RuntimeEnvironment runtimeEnvironment,
        EvaluationPlan plan,
        EvaluationDataset dataset,
        EvaluationRubric rubric
) {}
