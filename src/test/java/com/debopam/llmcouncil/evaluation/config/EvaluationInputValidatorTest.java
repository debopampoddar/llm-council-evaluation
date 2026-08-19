package com.debopam.llmcouncil.evaluation.config;

import com.debopam.llmcouncil.evaluation.TestFixtures;
import com.debopam.llmcouncil.evaluation.domain.EvaluationBundle;
import com.debopam.llmcouncil.evaluation.domain.EvaluationDataset;
import com.debopam.llmcouncil.evaluation.domain.EvaluationPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvaluationInputValidatorTest {
    @TempDir Path temp;
    private final EvaluationInputValidator validator = new EvaluationInputValidator();

    @Test
    void rejectsComparisonWithoutAnEnabledJudge() {
        EvaluationPlan base = TestFixtures.plan(temp);
        EvaluationPlan plan = copy(base, base.models(), base.variants(), base.comparisons(), List.of());

        var error = assertThrows(EvaluationConfigurationException.class,
                () -> validator.validate(bundle(plan, TestFixtures.evalCase())));

        assertTrue(error.errors().stream().anyMatch(value -> value.contains("enabled judge")));
    }

    @Test
    void rejectsPlansWithNoEnabledVariant() {
        EvaluationPlan base = TestFixtures.plan(temp);
        List<EvaluationPlan.VariantSpec> disabled = base.variants().stream()
                .map(value -> new EvaluationPlan.VariantSpec(value.id(), value.displayName(), value.type(), false,
                        value.profileId(), value.depthMode(), value.modelId(), value.samples())).toList();
        EvaluationPlan plan = copy(base, base.models(), disabled, List.of(), List.of());

        var error = assertThrows(EvaluationConfigurationException.class,
                () -> validator.validate(bundle(plan, TestFixtures.evalCase())));

        assertTrue(error.errors().stream().anyMatch(value -> value.contains("enabled variant")));
    }

    @Test
    void rejectsCloudBaseUrlsThatTheGatewayWouldIgnore() {
        EvaluationPlan base = TestFixtures.plan(temp);
        var cloud = new EvaluationPlan.ModelSpec("cloud", "openai", "gpt", "gpt",
                "https://example.test", 100, null, 0.1, 10, 0, 0, 1.0, 1.0);
        EvaluationPlan plan = copy(base, List.of(cloud),
                List.of(new EvaluationPlan.VariantSpec("direct", "Direct", EvaluationPlan.VariantType.DIRECT,
                        true, null, null, "cloud", null)), List.of(), List.of());

        var error = assertThrows(EvaluationConfigurationException.class,
                () -> validator.validate(bundle(plan, TestFixtures.evalCase())));

        assertTrue(error.errors().stream().anyMatch(value -> value.contains("supported only for Ollama")));
    }

    @Test
    void rejectsContextWindowsThatNonOllamaGatewaysWouldIgnore() {
        EvaluationPlan base = TestFixtures.plan(temp);
        var cloud = new EvaluationPlan.ModelSpec("cloud", "openai", "gpt", "gpt",
                null, 100, 16_384, 0.1, 10, 0, 0, 1.0, 1.0);
        EvaluationPlan plan = copy(base, List.of(cloud),
                List.of(new EvaluationPlan.VariantSpec("direct", "Direct", EvaluationPlan.VariantType.DIRECT,
                        true, null, null, "cloud", null)), List.of(), List.of());

        var error = assertThrows(EvaluationConfigurationException.class,
                () -> validator.validate(bundle(plan, TestFixtures.evalCase())));

        assertTrue(error.errors().stream().anyMatch(value -> value.contains(
                "contextWindowTokens is supported only for Ollama")));
    }

    @Test
    void rejectsBlankEvaluatorGuidanceBeforeLiveCalls() {
        EvaluationPlan base = TestFixtures.plan(temp);
        EvaluationDataset.EvaluationCase malformed = new EvaluationDataset.EvaluationCase(
                "case-1", "reasoning", "question", null, List.of(), List.of(" "),
                List.of(), List.of(), List.of(), java.util.Map.of());

        var error = assertThrows(EvaluationConfigurationException.class,
                () -> validator.validate(bundle(base, malformed)));

        assertTrue(error.errors().stream().anyMatch(value -> value.contains("requirements contains a blank")));
    }

    @Test
    void rejectsMoreThanOnePrimaryComparison() {
        EvaluationPlan base = TestFixtures.plan(temp);
        List<EvaluationPlan.ComparisonSpec> comparisons = List.of(
                new EvaluationPlan.ComparisonSpec("first", "direct", "council", true, true),
                new EvaluationPlan.ComparisonSpec("second", "direct", "council", true, true));
        EvaluationPlan plan = copy(base, base.models(), base.variants(), comparisons, base.judges());

        var error = assertThrows(EvaluationConfigurationException.class,
                () -> validator.validate(bundle(plan, TestFixtures.evalCase())));

        assertTrue(error.errors().stream().anyMatch(value -> value.contains("at most one primary")));
    }

    private EvaluationPlan copy(EvaluationPlan base, List<EvaluationPlan.ModelSpec> models,
                                List<EvaluationPlan.VariantSpec> variants,
                                List<EvaluationPlan.ComparisonSpec> comparisons,
                                List<EvaluationPlan.JudgeSpec> judges) {
        return new EvaluationPlan(base.version(), base.id(), base.description(), base.councilBaseUrl(),
                base.dataset(), base.rubric(), base.outputDirectory(), base.seed(), base.repetitions(),
                base.execution(), models, variants, comparisons, judges);
    }

    private EvaluationBundle bundle(EvaluationPlan plan, EvaluationDataset.EvaluationCase evalCase) {
        return new EvaluationBundle(plan, new EvaluationDataset(1, "dataset", "test", List.of(evalCase)),
                TestFixtures.rubric(), temp.resolve("plan.yml"), temp.resolve("dataset.yml"),
                temp.resolve("rubric.yml"), temp);
    }
}
