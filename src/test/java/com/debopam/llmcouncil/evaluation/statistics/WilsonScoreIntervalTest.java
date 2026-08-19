package com.debopam.llmcouncil.evaluation.statistics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WilsonScoreIntervalTest {
    @Test
    void averagesRepetitionsWithinCaseBeforeComputingInterval() {
        var interval = WilsonScoreInterval.interval(Map.of(
                "case-a", List.of(1.0, 1.0, 0.0),
                "case-b", List.of(0.0)));
        assertEquals(1.0 / 3.0, interval.estimate(), 0.0001);
        assertEquals(2, interval.cases());
        assertTrue(interval.lower95() <= interval.estimate());
        assertTrue(interval.upper95() >= interval.estimate());
    }

    @Test
    void allWinsInSmallSampleDoesNotProduceDegenerateCertainty() {
        var interval = WilsonScoreInterval.interval(Map.of(
                "a", List.of(1.0), "b", List.of(1.0), "c", List.of(1.0),
                "d", List.of(1.0), "e", List.of(1.0), "f", List.of(1.0),
                "g", List.of(1.0)));
        assertEquals(1.0, interval.estimate());
        assertTrue(interval.lower95() < 0.7);
        assertEquals(1.0, interval.upper95());
    }
}
