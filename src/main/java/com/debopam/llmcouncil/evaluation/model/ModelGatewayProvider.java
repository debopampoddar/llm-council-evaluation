package com.debopam.llmcouncil.evaluation.model;

import com.debopam.llmcouncil.evaluation.domain.EvaluationPlan;

@FunctionalInterface
public interface ModelGatewayProvider {
    ModelGateway gateway(EvaluationPlan.ModelSpec model);

    /** Read-only configuration preflight. Implementations may also check local model availability. */
    default void validate(EvaluationPlan.ModelSpec model) {
        gateway(model);
    }
}
