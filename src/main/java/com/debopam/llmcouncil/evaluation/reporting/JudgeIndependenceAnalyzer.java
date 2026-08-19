package com.debopam.llmcouncil.evaluation.reporting;

import com.debopam.llmcouncil.evaluation.domain.EvaluationPlan;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Reports judge overlap instead of presenting a participant as independent evidence. */
@Component
public class JudgeIndependenceAnalyzer {
    public List<Assessment> assess(EvaluationPlan plan, JsonNode catalog) {
        List<Assessment> results = new ArrayList<>();
        for (EvaluationPlan.ComparisonSpec comparison : plan.comparisons()) {
            if (Boolean.FALSE.equals(comparison.enabled())) continue;
            Set<Identity> evaluated = new LinkedHashSet<>();
            evaluated.addAll(identities(plan, catalog, variant(plan, comparison.left())));
            evaluated.addAll(identities(plan, catalog, variant(plan, comparison.right())));
            for (EvaluationPlan.JudgeSpec judge : plan.judges()) {
                if (Boolean.FALSE.equals(judge.enabled())) continue;
                EvaluationPlan.ModelSpec judgeModel = model(plan, judge.modelId());
                Identity judgeIdentity = new Identity(lower(judgeModel.provider()), judgeModel.providerModelId(),
                        lower(judgeModel.modelFamily()));
                Tier tier = Tier.INDEPENDENT;
                String detail = "No provider model id or declared family overlaps the evaluated variants.";
                if (evaluated.stream().anyMatch(value -> sameModel(value, judgeIdentity))) {
                    tier = Tier.OVERLAPPING_MODEL;
                    detail = "The judge's provider model id is used by an evaluated variant.";
                } else if (judgeIdentity.family() == null || judgeIdentity.family().isBlank()) {
                    tier = Tier.UNKNOWN;
                    detail = "The judge has no modelFamily, so family independence cannot be established.";
                } else if (evaluated.stream().anyMatch(value -> judgeIdentity.family().equals(value.family()))) {
                    tier = Tier.CORRELATED_FAMILY;
                    detail = "The judge shares a declared model family with an evaluated variant.";
                }
                results.add(new Assessment(comparison.id(), judge.id(), tier, detail));
            }
        }
        return List.copyOf(results);
    }

    private Set<Identity> identities(EvaluationPlan plan, JsonNode catalog, EvaluationPlan.VariantSpec variant) {
        if (variant.type() != EvaluationPlan.VariantType.COUNCIL) {
            EvaluationPlan.ModelSpec model = model(plan, variant.modelId());
            return Set.of(new Identity(lower(model.provider()), model.providerModelId(), lower(model.modelFamily())));
        }
        JsonNode profile = byId(catalog.path("profiles"), variant.profileId());
        String policyId = textIgnoreCase(profile.path("policyIdsByDepth"), variant.depthMode());
        JsonNode policy = byId(catalog.path("policies"), policyId);
        Set<String> ids = new LinkedHashSet<>();
        policy.path("memberModelIds").forEach(value -> ids.add(value.asText()));
        ids.add(policy.path("chairModelId").asText());
        String validator = policy.path("validatorModelId").asText("");
        if (!validator.isBlank()) ids.add(validator);
        Set<Identity> result = new LinkedHashSet<>();
        for (String id : ids) {
            JsonNode model = byId(catalog.path("models"), id);
            result.add(new Identity(lower(model.path("provider").asText()),
                    model.path("providerModelId").asText(), lower(model.path("modelFamily").asText(null))));
        }
        return result;
    }

    private boolean sameModel(Identity left, Identity right) {
        return left.provider().equals(right.provider()) && left.providerModelId().equals(right.providerModelId());
    }
    private EvaluationPlan.VariantSpec variant(EvaluationPlan plan, String id) { return plan.variants().stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow(); }
    private EvaluationPlan.ModelSpec model(EvaluationPlan plan, String id) { return plan.models().stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow(); }
    private JsonNode byId(JsonNode array, String id) { for (JsonNode node : array) if (id.equals(node.path("id").asText())) return node; throw new IllegalArgumentException("Unknown catalog id: " + id); }
    private String textIgnoreCase(JsonNode object, String key) { var fields = object.properties().iterator(); while (fields.hasNext()) { var field=fields.next(); if (field.getKey().equalsIgnoreCase(key)) return field.getValue().asText(); } throw new IllegalArgumentException("Missing mapping for " + key); }
    private String lower(String value) { return value == null ? null : value.toLowerCase(Locale.ROOT); }

    private record Identity(String provider, String providerModelId, String family) {}
    public enum Tier { INDEPENDENT, CORRELATED_FAMILY, OVERLAPPING_MODEL, UNKNOWN }
    public record Assessment(String comparisonId, String judgeId, Tier tier, String detail) {}
}
