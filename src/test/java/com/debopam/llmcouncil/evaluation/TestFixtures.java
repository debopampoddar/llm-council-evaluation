package com.debopam.llmcouncil.evaluation;

import com.debopam.llmcouncil.evaluation.domain.EvaluationBundle;
import com.debopam.llmcouncil.evaluation.domain.EvaluationDataset;
import com.debopam.llmcouncil.evaluation.domain.EvaluationPlan;
import com.debopam.llmcouncil.evaluation.domain.EvaluationRubric;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class TestFixtures {
    private TestFixtures() {}

    public static EvaluationPlan.ModelSpec model(String id) {
        return new EvaluationPlan.ModelSpec(id, "mock", id, id, null,
                500, null, 0.1, 10, 0, 0, 0.0, 0.0);
    }

    public static EvaluationDataset.EvaluationCase evalCase() {
        return new EvaluationDataset.EvaluationCase("case-1", "reasoning", "What is 2+2?", "Use integers.",
                List.of("math"), List.of("Return four"), List.of("2+2=4"), List.of("Anything except four"),
                List.of(new EvaluationDataset.CheckSpec("non-blank", null, null, null, null, null)), Map.of());
    }

    public static EvaluationRubric rubric() {
        return new EvaluationRubric(1, "general", "test", List.of(
                new EvaluationRubric.Criterion("correctness", "correct", 0.5),
                new EvaluationRubric.Criterion("clarity", "clear", 0.5)));
    }

    public static EvaluationPlan plan(Path output) {
        return new EvaluationPlan(1, "test-plan", "test", "http://127.0.0.1:8080",
                "dataset.yml", "rubric.yml", output.toString(), 42L, 1,
                new EvaluationPlan.ExecutionSettings(100, 0.0, 30, 1, true, true, false),
                List.of(model("direct-model"), model("judge-model")),
                List.of(
                        new EvaluationPlan.VariantSpec("direct", "Direct", EvaluationPlan.VariantType.DIRECT,
                                true, null, null, "direct-model", null),
                        new EvaluationPlan.VariantSpec("council", "Council", EvaluationPlan.VariantType.COUNCIL,
                                true, "local", "BALANCED", null, null)),
                List.of(new EvaluationPlan.ComparisonSpec("direct-vs-council", "direct", "council", true)),
                List.of(new EvaluationPlan.JudgeSpec("judge", "judge-model", true, true)));
    }

    public static EvaluationBundle bundle(Path output) {
        return new EvaluationBundle(plan(output),
                new EvaluationDataset(1, "dataset", "test", List.of(evalCase())), rubric(),
                output.resolve("plan.yml"), output.resolve("dataset.yml"), output.resolve("rubric.yml"), output);
    }

    public static JsonNode catalog() {
        try {
            return new ObjectMapper().readTree("""
                    {
                      "generation":1,"builtAt":"2026-08-18T00:00:00Z",
                      "profiles":[{"id":"local","policyIdsByDepth":{"BALANCED":"local-balanced"}}],
                      "policies":[{"id":"local-balanced","protocolId":"balanced","memberModelIds":["m1","m2"],"chairModelId":"m1","validatorModelId":"m2"}],
                      "models":[
                        {"id":"m1","provider":"ollama","providerModelId":"model-1","modelFamily":"family-1"},
                        {"id":"m2","provider":"ollama","providerModelId":"model-2","modelFamily":"family-2"}
                      ],
                      "protocols":[{"id":"balanced","orderedStages":["GENERATE","ANONYMIZE","REVIEW","SCORE","SYNTHESIZE","VALIDATE"],"stageOptions":{}}],
                      "providers":[],"issues":[]
                    }
                    """);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
