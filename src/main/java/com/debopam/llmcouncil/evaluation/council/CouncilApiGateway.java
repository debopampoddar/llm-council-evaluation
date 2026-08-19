package com.debopam.llmcouncil.evaluation.council;

import com.debopam.llmcouncil.evaluation.domain.EvaluationDataset;
import com.debopam.llmcouncil.evaluation.domain.EvaluationPlan;
import com.fasterxml.jackson.databind.JsonNode;

public interface CouncilApiGateway {
    JsonNode catalog(EvaluationPlan plan);
    JsonNode health(EvaluationPlan plan, EvaluationPlan.VariantSpec variant);
    JsonNode run(EvaluationPlan plan, EvaluationPlan.VariantSpec variant,
                 EvaluationDataset.EvaluationCase evalCase);
}
