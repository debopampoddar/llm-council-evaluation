package com.debopam.llmcouncil.evaluation.domain;

import java.util.List;

/** Shared scoring rubric used by blind model and human judges. */
public record EvaluationRubric(
        Integer version,
        String id,
        String description,
        List<Criterion> criteria
) {
    public static final int SUPPORTED_VERSION = 1;

    public record Criterion(String id, String description, Double weight) {}
}
