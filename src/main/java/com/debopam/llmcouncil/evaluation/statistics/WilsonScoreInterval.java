package com.debopam.llmcouncil.evaluation.statistics;

import java.util.List;
import java.util.Map;

/**
 * Wilson 95% interval over case-level tie-adjusted preferences.
 *
 * <p>Repetitions are averaged within each case before the interval is
 * calculated, so repeated samples are not misrepresented as independent cases.
 * Unlike a percentile bootstrap, this interval does not collapse to 100%-100%
 * merely because every resolved observation in a small pilot is a win.
 */
public final class WilsonScoreInterval {
    private static final double Z_95 = 1.959963984540054;

    private WilsonScoreInterval() {}

    public static Interval interval(Map<String, List<Double>> observationsByCase) {
        List<Double> caseMeans = observationsByCase.values().stream()
                .filter(values -> !values.isEmpty())
                .map(values -> values.stream().mapToDouble(Double::doubleValue).average().orElseThrow())
                .toList();
        if (caseMeans.isEmpty()) return new Interval(Double.NaN, Double.NaN, Double.NaN, 0);
        double estimate = caseMeans.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        int cases = caseMeans.size();
        double z2 = Z_95 * Z_95;
        double denominator = 1 + z2 / cases;
        double center = (estimate + z2 / (2 * cases)) / denominator;
        double margin = Z_95 * Math.sqrt(estimate * (1 - estimate) / cases
                + z2 / (4.0 * cases * cases)) / denominator;
        return new Interval(estimate, Math.max(0, center - margin),
                Math.min(1, center + margin), cases);
    }

    public record Interval(double estimate, double lower95, double upper95, int cases) {}
}
