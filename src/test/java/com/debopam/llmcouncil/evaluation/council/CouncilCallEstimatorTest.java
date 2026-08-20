package com.debopam.llmcouncil.evaluation.council;

import com.debopam.llmcouncil.evaluation.TestFixtures;
import com.debopam.llmcouncil.evaluation.domain.EvaluationPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CouncilCallEstimatorTest {
    @Test
    void estimatesBalancedTopologyFromLiveCatalogShape() {
        var variant = new EvaluationPlan.VariantSpec("council", "Council",
                EvaluationPlan.VariantType.COUNCIL, true, "local", "BALANCED", null, null);
        var range = new CouncilCallEstimator().estimate(TestFixtures.catalog(), variant);
        assertEquals(6, range.minimum());
        assertEquals(9, range.maximum());
    }

    @Test
    void forcedDebateIncludesMinimumRoundsAndPostDebateStages() throws Exception {
        var catalog = new ObjectMapper().readTree("""
                {"profiles":[{"id":"local","policyIdsByDepth":{"RIGOROUS":"p"}}],
                 "policies":[{"id":"p","protocolId":"rigorous","memberModelIds":["a","b"],"validatorModelId":"b"}],
                 "protocols":[{"id":"rigorous","orderedStages":["GENERATE","REVIEW","SCORE","DEBATE","REVISE","REVIEW_POST_DEBATE","SCORE","SYNTHESIZE","VALIDATE"],
                   "stageOptions":{"DEBATE":{"force-run":true,"min-rounds":2,"max-rounds":3}}}]}
                """);
        var variant = new EvaluationPlan.VariantSpec("rigorous", "Rigorous",
                EvaluationPlan.VariantType.COUNCIL, true, "local", "RIGOROUS", null, null);

        var range = new CouncilCallEstimator().estimate(catalog, variant);

        assertEquals(14, range.minimum());
        assertEquals(21, range.maximum());
    }
}
