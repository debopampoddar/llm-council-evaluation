package com.debopam.llmcouncil.evaluation.config;

import java.util.List;

/** Aggregates input errors so users can fix a plan in one edit. */
public class EvaluationConfigurationException extends RuntimeException {
    private final List<String> errors;

    public EvaluationConfigurationException(List<String> errors) {
        super(String.join(System.lineSeparator(), errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
