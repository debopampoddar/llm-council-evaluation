package com.debopam.llmcouncil.evaluation.domain;

import java.util.List;
import java.util.Locale;
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
            String contextPurpose,
            List<String> tags,
            List<String> requirements,
            List<String> referenceFacts,
            List<String> redFlags,
            List<CheckSpec> deterministicChecks,
            Map<String, Double> rubricOverrides
    ) {
        /** Backward-compatible constructor for datasets that use ordinary evidence context. */
        public EvaluationCase(String id, String category, String question, String context,
                              List<String> tags, List<String> requirements,
                              List<String> referenceFacts, List<String> redFlags,
                              List<CheckSpec> deterministicChecks,
                              Map<String, Double> rubricOverrides) {
            this(id, category, question, context, "EVIDENCE", tags, requirements,
                    referenceFacts, redFlags, deterministicChecks, rubricOverrides);
        }

        /** Null from an older dataset has the same safe meaning as an omitted field. */
        public String effectiveContextPurpose() {
            return contextPurpose == null || contextPurpose.isBlank()
                    ? "EVIDENCE" : contextPurpose.strip().toUpperCase(Locale.ROOT);
        }
    }

    public record CheckSpec(
            String type,
            String value,
            List<String> values,
            String pattern,
            Integer max,
            Boolean caseSensitive
    ) {}
}
