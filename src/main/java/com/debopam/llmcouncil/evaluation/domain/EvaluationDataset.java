package com.debopam.llmcouncil.evaluation.domain;

import java.util.List;
import java.util.Map;

/** Versioned, self-contained evaluation questions and evaluator-only expectations. */
public record EvaluationDataset(
        Integer version,
        String id,
        String description,
        List<EvaluationCase> cases
) {
    public static final int SUPPORTED_VERSION = 1;

    public record EvaluationCase(
            String id,
            String category,
            String question,
            String context,
            List<String> tags,
            List<String> requirements,
            List<String> referenceFacts,
            List<String> redFlags,
            List<CheckSpec> deterministicChecks,
            Map<String, Double> rubricOverrides
    ) {}

    public record CheckSpec(
            String type,
            String value,
            List<String> values,
            String pattern,
            Integer max,
            Boolean caseSensitive
    ) {}
}
