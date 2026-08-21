package com.debopam.llmcouncil.evaluation.council;

import com.debopam.llmcouncil.evaluation.domain.EvaluationPlan;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Derives a conservative model-call range from the council's live catalog. */
@Component
public class CouncilCallEstimator {

    public CallRange estimate(JsonNode catalog, EvaluationPlan.VariantSpec variant) {
        JsonNode profile = byId(catalog.path("profiles"), variant.profileId(), "profile");
        JsonNode mapping = profile.path("policyIdsByDepth");
        String policyId = textIgnoreCase(mapping, variant.depthMode());
        if (policyId == null) throw new IllegalArgumentException("Profile '" + variant.profileId()
                + "' has no policy for " + variant.depthMode());
        JsonNode policy = byId(catalog.path("policies"), policyId, "policy");
        JsonNode protocol = byId(catalog.path("protocols"), policy.path("protocolId").asText(), "protocol");

        int members = policy.path("memberModelIds").size();
        boolean validator = !policy.path("validatorModelId").asText("").isBlank();
        JsonNode debateOptions = protocol.path("stageOptions").path("DEBATE");
        boolean forceDebate = debateOptions.path("force-run").asBoolean(false);
        int minimum = 0;
        int maximum = 0;
        for (JsonNode stageNode : protocol.path("orderedStages")) {
            String stage = stageNode.asText();
            int minimumStageCalls = switch (stage) {
                case "GENERATE", "AGGREGATE", "REVIEW", "REVISE", "REVIEW_POST_DEBATE" -> members;
                case "SYNTHESIZE" -> 1;
                case "VALIDATE" -> validator ? 1 : 0;
                default -> 0;
            };
            // Reviews can make one bounded targeted recovery call per reviewer.
            // Synthesis can make one bounded user-facing-output recovery.
            // Validation can make one bounded structured-output or trust
            // recovery. These attempts are real provider calls and belong in
            // the preflight ceiling even though recovery is normally unused.
            int maximumStageCalls = switch (stage) {
                case "REVIEW", "REVIEW_POST_DEBATE" -> minimumStageCalls * 2;
                case "SYNTHESIZE" -> 2;
                case "VALIDATE" -> validator ? 2 : 0;
                default -> minimumStageCalls;
            };
            if ("DEBATE".equals(stage)) {
                int rounds = debateOptions.path("max-rounds").asInt(3);
                if (forceDebate) minimum += members * debateOptions.path("min-rounds").asInt(2);
                maximum += members * rounds;
            } else if (dependentOnDebate(stage)) {
                if (forceDebate) minimum += minimumStageCalls;
                maximum += maximumStageCalls;
            } else {
                minimum += minimumStageCalls;
                maximum += maximumStageCalls;
            }
        }
        return new CallRange(minimum, maximum, policyId, protocol.path("id").asText());
    }

    private boolean dependentOnDebate(String stage) {
        return "REVISE".equals(stage) || "REVIEW_POST_DEBATE".equals(stage);
    }

    private JsonNode byId(JsonNode array, String id, String type) {
        for (JsonNode node : array) if (id.equals(node.path("id").asText())) return node;
        throw new IllegalArgumentException("Unknown council " + type + ": " + id);
    }

    private String textIgnoreCase(JsonNode object, String key) {
        java.util.Iterator<Map.Entry<String, JsonNode>> fields = object.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getKey().equalsIgnoreCase(key)) return field.getValue().asText();
        }
        return null;
    }

    public record CallRange(int minimum, int maximum, String policyId, String protocolId) {}
}
