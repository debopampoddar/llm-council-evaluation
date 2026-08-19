package com.debopam.llmcouncil.evaluation.execution;

import com.debopam.llmcouncil.evaluation.council.CouncilApiException;
import com.debopam.llmcouncil.evaluation.council.CouncilApiGateway;
import com.debopam.llmcouncil.evaluation.domain.AnswerResult;
import com.debopam.llmcouncil.evaluation.domain.EvaluationDataset;
import com.debopam.llmcouncil.evaluation.domain.EvaluationPlan;
import com.debopam.llmcouncil.evaluation.domain.UsageMetrics;
import com.debopam.llmcouncil.evaluation.model.ModelGateway;
import com.debopam.llmcouncil.evaluation.model.ModelGatewayException;
import com.debopam.llmcouncil.evaluation.model.ModelGatewayProvider;
import com.debopam.llmcouncil.evaluation.model.ModelPrompt;
import com.debopam.llmcouncil.evaluation.model.ModelResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Runs one variant while preserving failures and partial evidence as first-class outcomes. */
@Component
public class AnswerGenerator {
    private final CouncilApiGateway council;
    private final ModelGatewayProvider models;
    private final EvaluationPromptFactory prompts;

    public AnswerGenerator(CouncilApiGateway council, ModelGatewayProvider models,
                           EvaluationPromptFactory prompts) {
        this.council = council;
        this.models = models;
        this.prompts = prompts;
    }

    public AnswerResult generate(EvaluationPlan plan, EvaluationDataset.EvaluationCase evalCase,
                                 EvaluationPlan.VariantSpec variant, int repetition) {
        String unitId = evalCase.id() + ":" + variant.id() + ":r" + repetition;
        Instant started = Instant.now();
        try {
            return switch (variant.type()) {
                case DIRECT -> direct(plan, evalCase, variant, repetition, unitId, started);
                case SAME_MODEL_ENSEMBLE -> ensemble(plan, evalCase, variant, repetition, unitId, started);
                case COUNCIL -> council(plan, evalCase, variant, repetition, unitId, started);
            };
        } catch (ModelGatewayException ex) {
            return failed(unitId, evalCase.id(), variant.id(), repetition, started,
                    ex.category(), ex.getMessage(), ex.usage(), List.of());
        } catch (CouncilApiException ex) {
            return failed(unitId, evalCase.id(), variant.id(), repetition, started,
                    "COUNCIL_API_ERROR", ex.getMessage(), 0);
        } catch (RuntimeException ex) {
            return failed(unitId, evalCase.id(), variant.id(), repetition, started,
                    "UNKNOWN", safe(ex), 0);
        }
    }

    public void validateModel(EvaluationPlan.ModelSpec model) {
        models.validate(model);
    }

    private AnswerResult direct(EvaluationPlan plan, EvaluationDataset.EvaluationCase evalCase,
                                EvaluationPlan.VariantSpec variant, int repetition,
                                String unitId, Instant started) {
        EvaluationPlan.ModelSpec model = model(plan, variant.modelId());
        ModelResponse response = models.gateway(model).call(new ModelPrompt(unitId,
                prompts.directSystem(), prompts.candidateUser(evalCase), false));
        return complete(unitId, evalCase.id(), variant.id(), repetition, started,
                response.text(), response.usage(), List.of(), null, List.of());
    }

    private AnswerResult ensemble(EvaluationPlan plan, EvaluationDataset.EvaluationCase evalCase,
                                  EvaluationPlan.VariantSpec variant, int repetition,
                                  String unitId, Instant started) {
        EvaluationPlan.ModelSpec model = model(plan, variant.modelId());
        ModelGateway gateway = models.gateway(model);
        List<String> candidates = new ArrayList<>();
        UsageMetrics usage = new UsageMetrics(0, 0, 0, null, false, false);
        for (int sample = 0; sample < variant.samples(); sample++) {
            try {
                ModelResponse response = gateway.call(new ModelPrompt(unitId + ":sample:" + sample,
                        prompts.directSystem(), prompts.candidateUser(evalCase), false));
                candidates.add(response.text());
                usage = usage.plus(response.usage());
            } catch (ModelGatewayException ex) {
                return failed(unitId, evalCase.id(), variant.id(), repetition, started,
                        ex.category(), ex.getMessage(), failedUsage(usage, ex), candidates);
            }
        }
        ModelResponse synthesis;
        try {
            synthesis = gateway.call(new ModelPrompt(unitId + ":synthesis",
                    prompts.ensembleSystem(), prompts.ensembleUser(evalCase, candidates), false));
            usage = usage.plus(synthesis.usage());
        } catch (ModelGatewayException ex) {
            return failed(unitId, evalCase.id(), variant.id(), repetition, started,
                    ex.category(), ex.getMessage(), failedUsage(usage, ex), candidates);
        }
        return complete(unitId, evalCase.id(), variant.id(), repetition, started,
                synthesis.text(), usage, candidates, null, List.of());
    }

    private AnswerResult council(EvaluationPlan plan, EvaluationDataset.EvaluationCase evalCase,
                                 EvaluationPlan.VariantSpec variant, int repetition,
                                 String unitId, Instant started) {
        JsonNode result = council.run(plan, variant, evalCase);
        String statusText = result.path("status").asText("FAILED");
        AnswerResult.AnswerStatus status = switch (statusText) {
            case "COMPLETED" -> AnswerResult.AnswerStatus.COMPLETED;
            case "PARTIAL" -> AnswerResult.AnswerStatus.PARTIAL;
            case "CANCELLED" -> AnswerResult.AnswerStatus.CANCELLED;
            case "INTERRUPTED" -> AnswerResult.AnswerStatus.INTERRUPTED;
            default -> AnswerResult.AnswerStatus.FAILED;
        };
        List<String> warnings = strings(result.path("warnings"));
        String answer = result.path("answer").asText("");
        String failureCategory = nullable(result, "failureCategory");
        String failureReason = nullable(result, "failureReason");
        if ((status == AnswerResult.AnswerStatus.COMPLETED || status == AnswerResult.AnswerStatus.PARTIAL)
                && answer.isBlank()) {
            status = AnswerResult.AnswerStatus.FAILED;
            failureCategory = "EMPTY_RESPONSE";
            failureReason = "Council returned a blank answer";
        }
        return new AnswerResult(unitId, evalCase.id(), variant.id(), repetition,
                status, answer, started, Instant.now(),
                Duration.between(started, Instant.now()).toMillis(), usage(result.path("usage")),
                failureCategory, failureReason,
                warnings, List.of(), result);
    }

    private AnswerResult complete(String unitId, String caseId, String variantId, int repetition,
                                  Instant started, String answer, UsageMetrics usage,
                                  List<String> components, JsonNode councilResult, List<String> warnings) {
        Instant completed = Instant.now();
        if (answer == null || answer.isBlank()) {
            return new AnswerResult(unitId, caseId, variantId, repetition,
                    AnswerResult.AnswerStatus.FAILED, "", started, completed,
                    Duration.between(started, completed).toMillis(), usage,
                    "EMPTY_RESPONSE", "Model returned a blank answer", warnings,
                    List.copyOf(components), councilResult);
        }
        return new AnswerResult(unitId, caseId, variantId, repetition,
                AnswerResult.AnswerStatus.COMPLETED, answer, started, completed,
                Duration.between(started, completed).toMillis(), usage,
                null, null, warnings, components, councilResult);
    }

    private AnswerResult failed(String unitId, String caseId, String variantId, int repetition,
                                Instant started, String category, String reason, int attemptedCalls) {
        Instant completed = Instant.now();
        return new AnswerResult(unitId, caseId, variantId, repetition,
                AnswerResult.AnswerStatus.FAILED, "", started, completed,
                Duration.between(started, completed).toMillis(),
                new UsageMetrics(attemptedCalls, 0, 0, null, true, false),
                category, reason, List.of(), List.of(), null);
    }

    private AnswerResult failed(String unitId, String caseId, String variantId, int repetition,
                                Instant started, String category, String reason, UsageMetrics usage,
                                List<String> components) {
        Instant completed = Instant.now();
        return new AnswerResult(unitId, caseId, variantId, repetition,
                AnswerResult.AnswerStatus.FAILED, "", started, completed,
                Duration.between(started, completed).toMillis(), usage,
                category, reason, List.of(), List.copyOf(components), null);
    }

    private UsageMetrics failedUsage(UsageMetrics completed, ModelGatewayException failure) {
        return completed.plus(failure.usage());
    }

    private EvaluationPlan.ModelSpec model(EvaluationPlan plan, String id) {
        return plan.models().stream().filter(value -> value.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown model: " + id));
    }

    private UsageMetrics usage(JsonNode usage) {
        if (usage == null || usage.isMissingNode() || usage.isNull()) return UsageMetrics.empty();
        return new UsageMetrics(usage.path("calls").asInt(), usage.path("promptTokens").asLong(),
                usage.path("completionTokens").asLong(),
                usage.hasNonNull("estimatedCostUsd") ? usage.get("estimatedCostUsd").doubleValue() : null,
                usage.path("estimated").asBoolean(true), usage.path("partiallyPriced").asBoolean(false));
    }

    private List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array != null && array.isArray()) array.forEach(value -> values.add(value.asText()));
        return List.copyOf(values);
    }
    private String nullable(JsonNode node, String field) { return node.hasNonNull(field) ? node.get(field).asText() : null; }
    private String safe(Throwable value) { return value.getMessage() == null ? value.getClass().getSimpleName() : value.getMessage(); }
}
