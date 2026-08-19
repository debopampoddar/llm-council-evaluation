package com.debopam.llmcouncil.evaluation.statistics;

import com.debopam.llmcouncil.evaluation.TestFixtures;
import com.debopam.llmcouncil.evaluation.domain.AnswerResult;
import com.debopam.llmcouncil.evaluation.domain.JudgmentRecord;
import com.debopam.llmcouncil.evaluation.domain.UsageMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MetricCalculatorTest {
    @TempDir Path temp;
    private final MetricCalculator calculator = new MetricCalculator(new JudgmentAggregator());

    @Test
    void countsEligiblePairsWithMissingJudgeEvidenceAsUnresolved() {
        var metrics = calculator.calculate(TestFixtures.bundle(temp),
                List.of(answer("direct", 0.1), answer("council", 0.2)), List.of(), List.of());

        assertEquals(1, metrics.comparisons().getFirst().eligiblePairs());
        assertEquals(1, metrics.comparisons().getFirst().unresolved());
        assertEquals(1, metrics.comparisons().getFirst().missing());
        assertEquals(0, metrics.comparisons().getFirst().invalid());
        assertEquals(0, metrics.comparisons().getFirst().judgedPairs());
        assertEquals(0.0, metrics.comparisons().getFirst().unresolvedLowerBound());
        assertEquals(1.0, metrics.comparisons().getFirst().unresolvedUpperBound());
    }

    @Test
    void totalCostAndUsageIncludeMirroredJudgeCalls() {
        var metrics = calculator.calculate(TestFixtures.bundle(temp),
                List.of(answer("direct", 0.1), answer("council", 0.2)), List.of(),
                List.of(judgment(1, "direct", "council", JudgmentRecord.Winner.A),
                        judgment(2, "council", "direct", JudgmentRecord.Winner.B)));

        assertEquals(2, metrics.judgeUsage().calls());
        assertEquals(4, metrics.judgeUsage().tokens());
        assertEquals(0.1, metrics.judgeUsage().totalEstimatedCostUsd());
        assertEquals(0.4, metrics.totalEstimatedCostUsd());
        assertFalse(metrics.totalCostIncomplete());
    }

    private AnswerResult answer(String variant, double cost) {
        return new AnswerResult("case-1:" + variant + ":r1", "case-1", variant, 1,
                AnswerResult.AnswerStatus.COMPLETED, "four", Instant.now(), Instant.now(), 1,
                new UsageMetrics(1, 1, 1, cost, false, false), null, null,
                List.of(), List.of(), null);
    }

    private JudgmentRecord judgment(int orientation, String a, String b, JudgmentRecord.Winner winner) {
        return new JudgmentRecord("j" + orientation, "direct-vs-council:case-1:r1", "direct-vs-council",
                "case-1", 1, "judge", orientation, a, b, JudgmentRecord.Status.COMPLETED,
                winner, 0.8, Map.of("correctness", 80.0, "clarity", 80.0),
                Map.of("correctness", 70.0, "clarity", 70.0), List.of(), List.of(),
                "reason", "{}", null, Instant.now(), 2,
                new UsageMetrics(1, 1, 1, 0.05, false, false));
    }
}
