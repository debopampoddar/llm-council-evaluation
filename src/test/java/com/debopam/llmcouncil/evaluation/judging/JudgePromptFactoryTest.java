package com.debopam.llmcouncil.evaluation.judging;

import com.debopam.llmcouncil.evaluation.TestFixtures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JudgePromptFactoryTest {
    @Test
    void rendersExactRubricCriterionKeysAndDecimalConfidenceGuidance() {
        String prompt = new JudgePromptFactory().system(TestFixtures.rubric(), TestFixtures.evalCase());

        assertTrue(prompt.contains("\"correctness\": 0"));
        assertTrue(prompt.contains("\"clarity\": 0"));
        assertTrue(prompt.contains("use 0.95,\nnever 95"));
        assertFalse(prompt.contains("\"criterion-id\""));
    }
}
