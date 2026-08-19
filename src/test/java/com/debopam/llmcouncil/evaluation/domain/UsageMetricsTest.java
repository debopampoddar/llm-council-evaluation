package com.debopam.llmcouncil.evaluation.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class UsageMetricsTest {
    @Test
    void emptyAccumulatorDoesNotMakeFirstPricedCallPartiallyPriced() {
        UsageMetrics first = new UsageMetrics(1, 10, 5, 0.01, false, false);
        UsageMetrics result = UsageMetrics.empty().plus(first);
        assertEquals(first, result);
        assertFalse(result.partiallyPriced());
    }
}
