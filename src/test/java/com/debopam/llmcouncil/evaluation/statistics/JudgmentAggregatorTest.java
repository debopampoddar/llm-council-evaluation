package com.debopam.llmcouncil.evaluation.statistics;

import com.debopam.llmcouncil.evaluation.TestFixtures;
import com.debopam.llmcouncil.evaluation.domain.JudgmentRecord;
import com.debopam.llmcouncil.evaluation.domain.UsageMetrics;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JudgmentAggregatorTest {
    @Test
    void mapsMirroredLabelsBackToVariantsBeforeVoting() {
        var first = judgment(1, "direct", "council", JudgmentRecord.Winner.A);
        var mirror = judgment(2, "council", "direct", JudgmentRecord.Winner.B);
        var result = new JudgmentAggregator().aggregate(TestFixtures.plan(java.nio.file.Path.of("out")),
                List.of(first, mirror));
        assertEquals(JudgmentAggregator.OutcomeStatus.DECIDED, result.getFirst().status());
        assertEquals("direct", result.getFirst().winnerVariant());
    }

    @Test
    void contradictoryMirrorsArePositionUnstableNotTies() {
        var first = judgment(1, "direct", "council", JudgmentRecord.Winner.A);
        var mirror = judgment(2, "council", "direct", JudgmentRecord.Winner.A);
        var result = new JudgmentAggregator().aggregate(TestFixtures.plan(java.nio.file.Path.of("out")),
                List.of(first, mirror));
        assertEquals(JudgmentAggregator.OutcomeStatus.POSITION_UNSTABLE, result.getFirst().status());
    }

    private JudgmentRecord judgment(int orientation, String a, String b, JudgmentRecord.Winner winner) {
        return new JudgmentRecord("j" + orientation, "direct-vs-council:case-1:r1", "direct-vs-council",
                "case-1", 1, "judge", orientation, a, b, JudgmentRecord.Status.COMPLETED,
                winner, 0.8, Map.of("correctness", 80.0), Map.of("correctness", 70.0),
                List.of(), List.of(), "reason", "{}", null, Instant.now(), 1,
                new UsageMetrics(1, 1, 1, 0.0, false, false));
    }
}
