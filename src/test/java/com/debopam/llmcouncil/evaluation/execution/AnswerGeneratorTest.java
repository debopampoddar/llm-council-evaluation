package com.debopam.llmcouncil.evaluation.execution;

import com.debopam.llmcouncil.evaluation.TestFixtures;
import com.debopam.llmcouncil.evaluation.council.CouncilApiGateway;
import com.debopam.llmcouncil.evaluation.domain.AnswerResult;
import com.debopam.llmcouncil.evaluation.domain.EvaluationPlan;
import com.debopam.llmcouncil.evaluation.domain.UsageMetrics;
import com.debopam.llmcouncil.evaluation.model.ModelGatewayException;
import com.debopam.llmcouncil.evaluation.model.ModelResponse;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnswerGeneratorTest {
    @Test
    void ensembleFailureRetainsUsageAndCompletedSamples() {
        AtomicInteger calls = new AtomicInteger();
        var provider = (com.debopam.llmcouncil.evaluation.model.ModelGatewayProvider) model -> prompt -> {
            if (calls.getAndIncrement() == 0) {
                return new ModelResponse("sample one", 1,
                        new UsageMetrics(1, 10, 5, 0.01, false, false));
            }
            throw new ModelGatewayException("MODEL_TIMEOUT", "timed out", 2, true);
        };
        CouncilApiGateway council = new CouncilApiGateway() {
            public com.fasterxml.jackson.databind.JsonNode catalog(EvaluationPlan plan) { throw new UnsupportedOperationException(); }
            public com.fasterxml.jackson.databind.JsonNode health(EvaluationPlan plan, EvaluationPlan.VariantSpec variant) { throw new UnsupportedOperationException(); }
            public com.fasterxml.jackson.databind.JsonNode run(EvaluationPlan plan, EvaluationPlan.VariantSpec variant,
                                                               com.debopam.llmcouncil.evaluation.domain.EvaluationDataset.EvaluationCase evalCase) { throw new UnsupportedOperationException(); }
        };
        AnswerGenerator generator = new AnswerGenerator(council, provider, new EvaluationPromptFactory());
        EvaluationPlan base = TestFixtures.plan(Path.of("target/test-results"));
        EvaluationPlan plan = new EvaluationPlan(base.version(), base.id(), base.description(), base.councilBaseUrl(),
                base.dataset(), base.rubric(), base.outputDirectory(), base.seed(), base.repetitions(), base.execution(),
                base.models(), base.variants(), base.comparisons(), base.judges());
        EvaluationPlan.VariantSpec ensemble = new EvaluationPlan.VariantSpec("ensemble", "Ensemble",
                EvaluationPlan.VariantType.SAME_MODEL_ENSEMBLE, true, null, null, "direct-model", 3);

        AnswerResult result = generator.generate(plan, TestFixtures.evalCase(), ensemble, 1);

        assertEquals(AnswerResult.AnswerStatus.FAILED, result.status());
        assertEquals(3, result.usage().calls());
        assertEquals(15, result.usage().totalTokens());
        assertEquals(0.01, result.usage().estimatedCostUsd());
        assertEquals(1, result.componentAnswers().size());
    }

    @Test
    void blankSuccessfulProviderResponseIsARecordedFailure() {
        var provider = (com.debopam.llmcouncil.evaluation.model.ModelGatewayProvider) model -> prompt ->
                new ModelResponse("  ", 1, new UsageMetrics(1, 2, 0, 0.0, false, false));
        CouncilApiGateway council = new CouncilApiGateway() {
            public com.fasterxml.jackson.databind.JsonNode catalog(EvaluationPlan plan) { throw new UnsupportedOperationException(); }
            public com.fasterxml.jackson.databind.JsonNode health(EvaluationPlan plan, EvaluationPlan.VariantSpec variant) { throw new UnsupportedOperationException(); }
            public com.fasterxml.jackson.databind.JsonNode run(EvaluationPlan plan, EvaluationPlan.VariantSpec variant,
                                                               com.debopam.llmcouncil.evaluation.domain.EvaluationDataset.EvaluationCase evalCase) { throw new UnsupportedOperationException(); }
        };
        AnswerGenerator generator = new AnswerGenerator(council, provider, new EvaluationPromptFactory());
        EvaluationPlan plan = TestFixtures.plan(Path.of("target/test-results"));

        AnswerResult result = generator.generate(plan, TestFixtures.evalCase(), plan.variants().getFirst(), 1);

        assertEquals(AnswerResult.AnswerStatus.FAILED, result.status());
        assertEquals("EMPTY_RESPONSE", result.failureCategory());
        assertEquals(1, result.usage().calls());
    }
}
