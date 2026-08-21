package com.debopam.llmcouncil.evaluation.config;

import com.debopam.llmcouncil.evaluation.domain.EvaluationBundle;
import com.debopam.llmcouncil.evaluation.domain.EvaluationDataset;
import com.debopam.llmcouncil.evaluation.domain.EvaluationPlan;
import com.debopam.llmcouncil.evaluation.domain.EvaluationRubric;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Cross-file validation for references, bounds, identifiers, and rubric coverage. */
@Component
public class EvaluationInputValidator {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final Set<String> PROVIDERS = Set.of("ollama", "openai", "anthropic", "gemini", "mock");
    private static final Set<String> CHECKS = Set.of(
            "non-blank", "contains-all", "contains-any", "contains-none",
            "regex", "forbidden-regex", "max-chars");

    public void validate(EvaluationBundle bundle) {
        List<String> errors = new ArrayList<>();
        validatePlan(bundle.plan(), errors);
        validateDataset(bundle.dataset(), errors);
        validateRubric(bundle.rubric(), errors);
        crossValidate(bundle, errors);
        if (!errors.isEmpty()) throw new EvaluationConfigurationException(errors);
    }

    private void validatePlan(EvaluationPlan plan, List<String> errors) {
        if (plan == null) { errors.add("Plan is empty"); return; }
        if (!Integer.valueOf(EvaluationPlan.SUPPORTED_VERSION).equals(plan.version()))
            errors.add("Plan version must be " + EvaluationPlan.SUPPORTED_VERSION);
        id(plan.id(), "Plan id", errors);
        try {
            URI uri = URI.create(plan.councilBaseUrl());
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null)
                errors.add("councilBaseUrl must be an absolute HTTP(S) URL");
            if (uri.getUserInfo() != null) errors.add("councilBaseUrl must not embed credentials");
        } catch (Exception ex) { errors.add("councilBaseUrl must be a valid URL"); }
        positive(plan.repetitions(), 1, 20, "repetitions", errors);
        if (plan.seed() == null) errors.add("seed is required");
        if (plan.execution() == null) errors.add("execution settings are required");
        else {
            positive(plan.execution().maxCalls(), 1, 1_000_000, "execution.maxCalls", errors);
            positive(plan.execution().councilRequestTimeoutSeconds(), 1, 86_400,
                    "execution.councilRequestTimeoutSeconds", errors);
            optionalRange(plan.execution().judgeInvalidRetries(), 0, 3,
                    "execution.judgeInvalidRetries", errors);
            if (plan.execution().maxEstimatedCostUsd() != null
                    && (!Double.isFinite(plan.execution().maxEstimatedCostUsd())
                    || plan.execution().maxEstimatedCostUsd() < 0))
                errors.add("execution.maxEstimatedCostUsd must be finite and >= 0");
        }

        unique(plan.models(), EvaluationPlan.ModelSpec::id, "model", errors);
        unique(plan.variants(), EvaluationPlan.VariantSpec::id, "variant", errors);
        unique(plan.comparisons(), EvaluationPlan.ComparisonSpec::id, "comparison", errors);
        unique(plan.judges(), EvaluationPlan.JudgeSpec::id, "judge", errors);

        if (list(plan.variants()).stream().filter(java.util.Objects::nonNull)
                .noneMatch(variant -> !Boolean.FALSE.equals(variant.enabled())))
            errors.add("Plan must contain at least one enabled variant");

        list(plan.models()).forEach(model -> {
            if (model == null) { errors.add("Model list contains a null entry"); return; }
            id(model.id(), "Model id", errors);
            if (!PROVIDERS.contains(lower(model.provider())))
                errors.add("Model '" + model.id() + "' has unsupported provider: " + model.provider());
            required(model.providerModelId(), "Model '" + model.id() + "' providerModelId", errors);
            validateModelBaseUrl(model, errors);
            positive(model.maxOutputTokens(), 1, 100_000, "Model '" + model.id() + "' maxOutputTokens", errors);
            optionalRange(model.contextWindowTokens(), 1_024, 1_000_000,
                    "Model '" + model.id() + "' contextWindowTokens", errors);
            if (model.contextWindowTokens() != null && !"ollama".equals(lower(model.provider()))) {
                errors.add("Model '" + model.id() + "' contextWindowTokens is supported only for Ollama");
            }
            positive(model.timeoutSeconds(), 1, 86_400, "Model '" + model.id() + "' timeoutSeconds", errors);
            optionalRange(model.retryMaxAttempts(), 0, 5, "Model '" + model.id() + "' retryMaxAttempts", errors);
            optionalRange(model.retryBaseDelayMs(), 0, 60_000, "Model '" + model.id() + "' retryBaseDelayMs", errors);
            if (model.temperature() == null || !Double.isFinite(model.temperature())
                    || model.temperature() < 0 || model.temperature() > 2)
                errors.add("Model '" + model.id() + "' temperature must be between 0 and 2");
            nonNegative(model.costPer1kInputTokens(), "Model '" + model.id() + "' input price", errors);
            nonNegative(model.costPer1kOutputTokens(), "Model '" + model.id() + "' output price", errors);
        });
        list(plan.variants()).forEach(variant -> {
            if (variant == null) { errors.add("Variant list contains a null entry"); return; }
            id(variant.id(), "Variant id", errors);
            if (variant.type() == null) { errors.add("Variant '" + variant.id() + "' type is required"); return; }
            switch (variant.type()) {
                case COUNCIL -> {
                    required(variant.profileId(), "Council variant '" + variant.id() + "' profileId", errors);
                    if (!Set.of("QUICK", "BALANCED", "RIGOROUS").contains(upper(variant.depthMode())))
                        errors.add("Council variant '" + variant.id() + "' depthMode must be QUICK, BALANCED, or RIGOROUS");
                }
                case DIRECT -> required(variant.modelId(), "Direct variant '" + variant.id() + "' modelId", errors);
                case SAME_MODEL_ENSEMBLE -> {
                    required(variant.modelId(), "Ensemble variant '" + variant.id() + "' modelId", errors);
                    positive(variant.samples(), 2, 20, "Ensemble variant '" + variant.id() + "' samples", errors);
                }
            }
        });
    }

    private void validateDataset(EvaluationDataset dataset, List<String> errors) {
        if (dataset == null) { errors.add("Dataset is empty"); return; }
        if (!Integer.valueOf(EvaluationDataset.SUPPORTED_VERSION).equals(dataset.version()))
            errors.add("Dataset version must be " + EvaluationDataset.SUPPORTED_VERSION);
        id(dataset.id(), "Dataset id", errors);
        unique(dataset.cases(), EvaluationDataset.EvaluationCase::id, "case", errors);
        if (list(dataset.cases()).isEmpty()) errors.add("Dataset must contain at least one case");
        list(dataset.cases()).forEach(evalCase -> {
            if (evalCase == null) { errors.add("Dataset cases contain a null entry"); return; }
            id(evalCase.id(), "Case id", errors);
            required(evalCase.category(), "Case '" + evalCase.id() + "' category", errors);
            required(evalCase.question(), "Case '" + evalCase.id() + "' question", errors);
            if (evalCase.question() != null && evalCase.question().length() > 5_000)
                errors.add("Case '" + evalCase.id() + "' question exceeds the council API limit of 5000 characters");
            if (evalCase.context() != null && evalCase.context().length() > 10_000)
                errors.add("Case '" + evalCase.id() + "' context exceeds the council API limit of 10000 characters");
            if (!Set.of("EVIDENCE", "ANALYSIS_SUBJECT")
                    .contains(upper(evalCase.effectiveContextPurpose())))
                errors.add("Case '" + evalCase.id()
                        + "' contextPurpose must be EVIDENCE or ANALYSIS_SUBJECT");
            stringList(evalCase.tags(), "Case '" + evalCase.id() + "' tags", errors);
            stringList(evalCase.requirements(), "Case '" + evalCase.id() + "' requirements", errors);
            stringList(evalCase.referenceFacts(), "Case '" + evalCase.id() + "' referenceFacts", errors);
            stringList(evalCase.redFlags(), "Case '" + evalCase.id() + "' redFlags", errors);
            int index = 0;
            for (EvaluationDataset.CheckSpec check : list(evalCase.deterministicChecks())) {
                String location = "Case '" + evalCase.id() + "' check " + index++;
                if (check == null) { errors.add(location + " is null"); continue; }
                if (!CHECKS.contains(lower(check.type()))) errors.add(location + " has unknown type: " + check.type());
                if (Set.of("contains-all", "contains-any", "contains-none").contains(lower(check.type()))
                        && (check.value() == null || check.value().isBlank()) && list(check.values()).isEmpty())
                    errors.add(location + " requires value or values");
                if (Set.of("contains-all", "contains-any", "contains-none").contains(lower(check.type()))) {
                    if (check.value() != null && check.value().isBlank()) errors.add(location + " value must not be blank");
                    stringList(check.values(), location + " values", errors);
                }
                if (Set.of("regex", "forbidden-regex").contains(lower(check.type()))) {
                    try { Pattern.compile(check.pattern()); }
                    catch (Exception ex) { errors.add(location + " has invalid pattern"); }
                }
                if ("max-chars".equals(lower(check.type())) && (check.max() == null || check.max() < 1))
                    errors.add(location + " max must be positive");
            }
        });
    }

    private void validateRubric(EvaluationRubric rubric, List<String> errors) {
        if (rubric == null) { errors.add("Rubric is empty"); return; }
        if (!Integer.valueOf(EvaluationRubric.SUPPORTED_VERSION).equals(rubric.version()))
            errors.add("Rubric version must be " + EvaluationRubric.SUPPORTED_VERSION);
        id(rubric.id(), "Rubric id", errors);
        unique(rubric.criteria(), EvaluationRubric.Criterion::id, "rubric criterion", errors);
        double total = 0;
        for (EvaluationRubric.Criterion criterion : list(rubric.criteria())) {
            if (criterion == null) { errors.add("Rubric criteria contain a null entry"); continue; }
            id(criterion.id(), "Criterion id", errors);
            required(criterion.description(), "Criterion '" + criterion.id() + "' description", errors);
            if (criterion.weight() == null || !Double.isFinite(criterion.weight())
                    || criterion.weight() <= 0 || criterion.weight() > 1)
                errors.add("Criterion '" + criterion.id() + "' weight must be > 0 and <= 1");
            else total += criterion.weight();
        }
        if (Math.abs(total - 1.0) > 0.000_001) errors.add("Rubric criterion weights must sum to 1.0, got " + total);
    }

    private void crossValidate(EvaluationBundle bundle, List<String> errors) {
        EvaluationPlan plan = bundle.plan();
        if (plan == null || bundle.dataset() == null || bundle.rubric() == null) return;
        Set<String> models = ids(list(plan.models()).stream().filter(java.util.Objects::nonNull)
                .map(EvaluationPlan.ModelSpec::id).toList());
        Set<String> variants = ids(list(plan.variants()).stream().filter(java.util.Objects::nonNull)
                .map(EvaluationPlan.VariantSpec::id).toList());
        list(plan.variants()).stream().filter(java.util.Objects::nonNull)
                .filter(v -> v.type() != EvaluationPlan.VariantType.COUNCIL)
                .filter(v -> v.modelId() != null && !models.contains(v.modelId()))
                .forEach(v -> errors.add("Variant '" + v.id() + "' references unknown model '" + v.modelId() + "'"));
        list(plan.judges()).forEach(j -> {
            if (j == null) return;
            id(j.id(), "Judge id", errors);
            if (!models.contains(j.modelId())) errors.add("Judge '" + j.id() + "' references unknown model '" + j.modelId() + "'");
        });
        list(plan.comparisons()).forEach(c -> {
            if (c == null) return;
            id(c.id(), "Comparison id", errors);
            if (!variants.contains(c.left())) errors.add("Comparison '" + c.id() + "' has unknown left variant '" + c.left() + "'");
            if (!variants.contains(c.right())) errors.add("Comparison '" + c.id() + "' has unknown right variant '" + c.right() + "'");
            if (c.left() != null && c.left().equals(c.right())) errors.add("Comparison '" + c.id() + "' compares a variant to itself");
            if (!Boolean.FALSE.equals(c.enabled())) {
                list(plan.variants()).stream().filter(java.util.Objects::nonNull)
                        .filter(v -> (v.id().equals(c.left()) || v.id().equals(c.right())) && Boolean.FALSE.equals(v.enabled()))
                        .forEach(v -> errors.add("Enabled comparison '" + c.id() + "' references disabled variant '" + v.id() + "'"));
            }
            if (Boolean.TRUE.equals(c.primary()) && Boolean.FALSE.equals(c.enabled()))
                errors.add("Primary comparison '" + c.id() + "' must be enabled");
        });
        long primaryComparisons = list(plan.comparisons()).stream().filter(java.util.Objects::nonNull)
                .filter(c -> Boolean.TRUE.equals(c.primary())).count();
        if (primaryComparisons > 1) errors.add("Plan may declare at most one primary comparison");
        boolean hasEnabledComparison = list(plan.comparisons()).stream().filter(java.util.Objects::nonNull)
                .anyMatch(c -> !Boolean.FALSE.equals(c.enabled()));
        boolean hasEnabledJudge = list(plan.judges()).stream().filter(java.util.Objects::nonNull)
                .anyMatch(j -> !Boolean.FALSE.equals(j.enabled()));
        if (hasEnabledComparison && !hasEnabledJudge)
            errors.add("At least one enabled judge is required when a comparison is enabled");
        Set<String> criteria = ids(list(bundle.rubric().criteria()).stream().filter(java.util.Objects::nonNull)
                .map(EvaluationRubric.Criterion::id).toList());
        list(bundle.dataset().cases()).stream().filter(java.util.Objects::nonNull).forEach(c -> map(c.rubricOverrides()).keySet().stream()
                .filter(id -> !criteria.contains(id))
                .forEach(id -> errors.add("Case '" + c.id() + "' overrides unknown criterion '" + id + "'")));
        list(bundle.dataset().cases()).stream().filter(java.util.Objects::nonNull).forEach(c -> {
            map(c.rubricOverrides()).forEach((criterion, weight) -> {
                if (weight == null || !Double.isFinite(weight) || weight <= 0 || weight > 1)
                    errors.add("Case '" + c.id() + "' rubric override '" + criterion + "' must be > 0 and <= 1");
            });
            double effectiveTotal = list(bundle.rubric().criteria()).stream().filter(java.util.Objects::nonNull)
                    .mapToDouble(criterion -> {
                        Double overridden = map(c.rubricOverrides()).get(criterion.id());
                        return overridden == null
                                ? (criterion.weight() == null ? 0.0 : criterion.weight())
                                : overridden;
                    }).sum();
            if (Math.abs(effectiveTotal - 1.0) > 0.000_001)
                errors.add("Case '" + c.id() + "' effective rubric weights must sum to 1.0, got " + effectiveTotal);
        });
    }

    private <T> void unique(List<T> values, java.util.function.Function<T, String> id,
                            String name, List<String> errors) {
        Set<String> seen = new HashSet<>();
        for (T value : list(values)) {
            if (value == null) { errors.add("Null " + name + " entry"); continue; }
            String current = id.apply(value);
            if (current != null && !seen.add(current)) errors.add("Duplicate " + name + " id: " + current);
        }
    }

    private void id(String value, String label, List<String> errors) {
        if (value == null || !ID.matcher(value).matches()) errors.add(label + " must match " + ID.pattern());
    }
    private void required(String value, String label, List<String> errors) {
        if (value == null || value.isBlank()) errors.add(label + " is required");
    }
    private void positive(Integer value, int min, int max, String label, List<String> errors) {
        if (value == null || value < min || value > max) errors.add(label + " must be between " + min + " and " + max);
    }
    private void nonNegative(Double value, String label, List<String> errors) {
        if (value != null && (!Double.isFinite(value) || value < 0))
            errors.add(label + " must be finite and >= 0");
    }
    private void optionalRange(Integer value, int min, int max, String label, List<String> errors) {
        if (value != null && (value < min || value > max)) errors.add(label + " must be between " + min + " and " + max);
    }
    private void stringList(List<String> values, String label, List<String> errors) {
        for (int index = 0; index < list(values).size(); index++) {
            String value = values.get(index);
            if (value == null || value.isBlank()) errors.add(label + " contains a blank value at index " + index);
        }
    }
    private void validateModelBaseUrl(EvaluationPlan.ModelSpec model, List<String> errors) {
        if (model.baseUrl() == null || model.baseUrl().isBlank()) {
            if ("ollama".equals(lower(model.provider()))) {
                errors.add("Ollama model '" + model.id() + "' baseUrl is required");
            }
            return;
        }
        if (!"ollama".equals(lower(model.provider()))) {
            errors.add("Model '" + model.id() + "' baseUrl is supported only for Ollama; configure cloud endpoints through Spring properties");
            return;
        }
        try {
            URI uri = URI.create(model.baseUrl());
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null)
                errors.add("Model '" + model.id() + "' baseUrl must be an absolute HTTP(S) URL");
            if (uri.getUserInfo() != null)
                errors.add("Model '" + model.id() + "' baseUrl must not embed credentials");
        } catch (Exception ex) {
            errors.add("Model '" + model.id() + "' baseUrl must be a valid URL");
        }
    }
    private String lower(String value) { return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT); }
    private String upper(String value) { return value == null ? "" : value.toUpperCase(java.util.Locale.ROOT); }
    private Set<String> ids(List<String> values) {
        return values.stream().filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    private <T> List<T> list(List<T> values) { return values == null ? List.of() : values; }
    private <K,V> Map<K,V> map(Map<K,V> values) { return values == null ? Map.of() : values; }
}
