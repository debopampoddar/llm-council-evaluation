package com.debopam.llmcouncil.evaluation.domain;

import java.nio.file.Path;

/** Strictly loaded and validated experiment inputs plus their resolved paths. */
public record EvaluationBundle(
        EvaluationPlan plan,
        EvaluationDataset dataset,
        EvaluationRubric rubric,
        Path planPath,
        Path datasetPath,
        Path rubricPath,
        Path outputDirectory
) {}
