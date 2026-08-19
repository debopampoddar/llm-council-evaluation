package com.debopam.llmcouncil.evaluation.model;

import com.debopam.llmcouncil.evaluation.domain.UsageMetrics;

/** Deterministic provider used only by hermetic smoke plans and tests. */
public class MockModelGateway implements ModelGateway {
    private final String modelId;

    public MockModelGateway(String modelId) {
        this.modelId = modelId;
    }

    @Override
    public ModelResponse call(ModelPrompt prompt) {
        String text;
        if (prompt.system().contains("blind pairwise evaluator")) {
            text = """
                    {"winner":"TIE","confidence":0.8,
                     "scores":{"A":{"correctness":80,"completeness":80,"reasoning":80,"constraint-following":80,"clarity":80},
                               "B":{"correctness":80,"completeness":80,"reasoning":80,"constraint-following":80,"clarity":80}},
                     "violations":{"A":[],"B":[]},"rationale":"Equivalent mock answers."}
                    """;
        } else if (prompt.system().contains("synthesize the independent candidate answers")) {
            text = "Mock ensemble synthesis from " + modelId + ".";
        } else {
            text = "Mock answer from " + modelId + ": recommendation, reasons, assumptions, and constraints.";
        }
        return new ModelResponse(text, 1, new UsageMetrics(1, 20, 20, 0.0, false, false));
    }
}
