package com.debopam.llmcouncil.evaluation.checks;

import com.debopam.llmcouncil.evaluation.domain.AnswerResult;
import com.debopam.llmcouncil.evaluation.domain.CheckResult;
import com.debopam.llmcouncil.evaluation.domain.EvaluationDataset;
import com.debopam.llmcouncil.evaluation.domain.UsageMetrics;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeterministicCheckEngineTest {
    @Test
    void executesEverySupportedCheckWithoutInventingSemanticChecks() {
        var specs = List.of(
                check("non-blank", null, null, null, null, null),
                check("contains-all", null, List.of("alpha", "beta"), null, null, false),
                check("contains-any", null, List.of("missing", "beta"), null, null, false),
                check("contains-none", null, List.of("forbidden"), null, null, false),
                check("regex", null, null, "beta\\s+42", null, null),
                check("forbidden-regex", null, null, "secret", null, null),
                check("max-chars", null, null, null, 100, null));
        var evalCase = new EvaluationDataset.EvaluationCase("c", "x", "q", null, List.of(), List.of(), List.of(),
                List.of(), specs, Map.of());
        var answer = new AnswerResult("u", "c", "v", 1, AnswerResult.AnswerStatus.COMPLETED,
                "Alpha beta 42", Instant.now(), Instant.now(), 1, UsageMetrics.empty(), null, null,
                List.of(), List.of(), null);
        List<CheckResult> results = new DeterministicCheckEngine().evaluate(evalCase, answer);
        assertEquals(7, results.size());
        assertEquals(7, results.stream().filter(value -> value.status() == CheckResult.Status.PASS).count());
    }

    private EvaluationDataset.CheckSpec check(String type, String value, List<String> values,
                                               String pattern, Integer max, Boolean sensitive) {
        return new EvaluationDataset.CheckSpec(type, value, values, pattern, max, sensitive);
    }
}
