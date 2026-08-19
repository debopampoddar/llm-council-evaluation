package com.debopam.llmcouncil.evaluation.judging;

import com.debopam.llmcouncil.evaluation.TestFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JudgeResponseParserTest {
    private final JudgeResponseParser parser = new JudgeResponseParser(new ObjectMapper());

    @Test
    void recoversFencedJsonAndRequiresExactCriterionCoverage() {
        String raw = """
                ```json
                {"winner":"A","confidence":0.8,
                 "scores":{"A":{"correctness":90,"clarity":80},"B":{"correctness":70,"clarity":85}},
                 "violations":{"A":[],"B":["incorrect result"]},"rationale":"A is correct."}
                ```
                """;
        var value = parser.parse(raw, TestFixtures.rubric());
        assertEquals(90, value.scoresA().get("correctness"));
    }

    @Test
    void rejectsMissingCriteriaAndOutOfRangeConfidence() {
        String raw = """
                {"winner":"TIE","confidence":2,
                 "scores":{"A":{"correctness":90},"B":{"correctness":90}},
                 "violations":{"A":[],"B":[]},"rationale":"tie"}
                """;
        assertThrows(IllegalArgumentException.class, () -> parser.parse(raw, TestFixtures.rubric()));
    }

    @Test
    void rejectsNonStringViolationEntries() {
        String raw = """
                {"winner":"TIE","confidence":0.5,
                 "scores":{"A":{"correctness":90,"clarity":90},"B":{"correctness":90,"clarity":90}},
                 "violations":{"A":[42],"B":[]},"rationale":"tie"}
                """;
        assertThrows(IllegalArgumentException.class, () -> parser.parse(raw, TestFixtures.rubric()));
    }
}
