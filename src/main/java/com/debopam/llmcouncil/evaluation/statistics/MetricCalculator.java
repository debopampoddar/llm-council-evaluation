package com.debopam.llmcouncil.evaluation.statistics;

import com.debopam.llmcouncil.evaluation.domain.AnswerResult;
import com.debopam.llmcouncil.evaluation.domain.CheckResult;
import com.debopam.llmcouncil.evaluation.domain.EvaluationBundle;
import com.debopam.llmcouncil.evaluation.domain.EvaluationPlan;
import com.debopam.llmcouncil.evaluation.domain.JudgmentRecord;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MetricCalculator {
    private final JudgmentAggregator aggregator;

    public MetricCalculator(JudgmentAggregator aggregator) {
        this.aggregator = aggregator;
    }

    public EvaluationMetrics calculate(EvaluationBundle bundle, List<AnswerResult> answers,
                                       List<CheckResult> checks, List<JudgmentRecord> judgments) {
        List<EvaluationMetrics.VariantMetric> variants = new ArrayList<>();
        for (EvaluationPlan.VariantSpec variant : bundle.plan().variants()) {
            if (Boolean.FALSE.equals(variant.enabled())) continue;
            List<AnswerResult> values = answers.stream().filter(value -> value.variantId().equals(variant.id())).toList();
            int completed = count(values, AnswerResult.AnswerStatus.COMPLETED);
            int partial = count(values, AnswerResult.AnswerStatus.PARTIAL);
            int failed = values.size() - completed - partial;
            double cost = values.stream().filter(value -> value.usage().estimatedCostUsd() != null)
                    .mapToDouble(value -> value.usage().estimatedCostUsd()).sum();
            boolean costIncomplete = values.stream().anyMatch(value -> value.usage().estimatedCostUsd() == null
                    || value.usage().partiallyPriced());
            variants.add(new EvaluationMetrics.VariantMetric(variant.id(), values.size(), completed, partial, failed,
                    ratio(completed + partial, values.size()), averageDuration(values),
                    values.stream().mapToInt(value -> value.usage().calls()).average().orElse(0),
                    values.stream().mapToLong(value -> value.usage().totalTokens()).average().orElse(0),
                    costIncomplete && cost == 0 ? null : round(cost), costIncomplete));
        }

        List<JudgmentAggregator.PairOutcome> outcomes = aggregator.aggregate(bundle.plan(), judgments);
        List<EvaluationMetrics.ComparisonMetric> comparisons = new ArrayList<>();
        for (EvaluationPlan.ComparisonSpec comparison : bundle.plan().comparisons()) {
            if (Boolean.FALSE.equals(comparison.enabled())) continue;
            List<JudgmentAggregator.PairOutcome> values = outcomes.stream()
                    .filter(value -> value.comparisonId().equals(comparison.id())).toList();
            int left = 0, right = 0, ties = 0;
            int positionUnstable = 0, judgeDisagreement = 0, invalid = 0;
            Map<String, List<Double>> byCase = new LinkedHashMap<>();
            for (JudgmentAggregator.PairOutcome value : values) {
                Double observation = null;
                if (value.status() == JudgmentAggregator.OutcomeStatus.TIE) { ties++; observation = 0.5; }
                else if (value.status() == JudgmentAggregator.OutcomeStatus.DECIDED) {
                    if (comparison.left().equals(value.winnerVariant())) { left++; observation = 1.0; }
                    else { right++; observation = 0.0; }
                } else if (value.status() == JudgmentAggregator.OutcomeStatus.POSITION_UNSTABLE) {
                    positionUnstable++;
                } else if (value.status() == JudgmentAggregator.OutcomeStatus.JUDGE_DISAGREEMENT) {
                    judgeDisagreement++;
                } else {
                    invalid++;
                }
                if (observation != null) byCase.computeIfAbsent(value.caseId(), ignored -> new ArrayList<>()).add(observation);
            }
            WilsonScoreInterval.Interval interval = WilsonScoreInterval.interval(byCase);
            int eligible = eligiblePairs(answers, comparison);
            int missing = Math.max(0, eligible - values.size());
            int unresolved = positionUnstable + judgeDisagreement + invalid + missing;
            int intended = bundle.dataset().cases().size() * bundle.plan().repetitions();
            Double estimate = interval.cases() == 0 ? null : interval.estimate();
            Double lower = interval.cases() == 0 ? null : interval.lower95();
            Double upper = interval.cases() == 0 ? null : interval.upper95();
            Double sensitivityLower = eligible == 0 ? null : (left + 0.5 * ties) / eligible;
            Double sensitivityUpper = eligible == 0 ? null : (left + 0.5 * ties + unresolved) / eligible;
            comparisons.add(new EvaluationMetrics.ComparisonMetric(comparison.id(), comparison.left(), comparison.right(),
                    intended, eligible, left + right + ties, left, right, ties, unresolved,
                    positionUnstable, judgeDisagreement, invalid, missing,
                    estimate, lower, upper, interval.cases(), sensitivityLower, sensitivityUpper));
        }

        List<EvaluationMetrics.CheckMetric> checkMetrics = new ArrayList<>();
        for (EvaluationPlan.VariantSpec variant : bundle.plan().variants()) {
            if (Boolean.FALSE.equals(variant.enabled())) continue;
            List<String> units = answers.stream().filter(value -> value.variantId().equals(variant.id()))
                    .map(AnswerResult::unitId).toList();
            List<CheckResult> values = checks.stream().filter(value -> units.contains(value.unitId())).toList();
            checkMetrics.add(new EvaluationMetrics.CheckMetric(variant.id(),
                    checkCount(values, CheckResult.Status.PASS), checkCount(values, CheckResult.Status.FAIL),
                    checkCount(values, CheckResult.Status.ERROR)));
        }
        int invalid = (int) judgments.stream().filter(value -> value.status() != JudgmentRecord.Status.COMPLETED).count();
        int judgeCalls = judgments.stream().mapToInt(value -> value.usage().calls()).sum();
        long judgeTokens = judgments.stream().mapToLong(value -> value.usage().totalTokens()).sum();
        double judgeCost = judgments.stream().filter(value -> value.usage().estimatedCostUsd() != null)
                .mapToDouble(value -> value.usage().estimatedCostUsd()).sum();
        boolean judgeCostIncomplete = judgments.stream().anyMatch(value -> value.usage().estimatedCostUsd() == null
                || value.usage().partiallyPriced());
        Double reportedJudgeCost = judgeCostIncomplete && judgeCost == 0 ? null : round(judgeCost);
        double answerCost = variants.stream().filter(value -> value.totalEstimatedCostUsd() != null)
                .mapToDouble(EvaluationMetrics.VariantMetric::totalEstimatedCostUsd).sum();
        boolean answerCostIncomplete = variants.stream().anyMatch(EvaluationMetrics.VariantMetric::costIncomplete);
        boolean totalIncomplete = answerCostIncomplete || judgeCostIncomplete;
        double totalCost = answerCost + judgeCost;
        Double reportedTotalCost = totalIncomplete && totalCost == 0 ? null : round(totalCost);
        EvaluationMetrics.JudgeMetric judgeMetric = new EvaluationMetrics.JudgeMetric(judgments.size(), judgeCalls,
                judgeTokens, judgments.stream().mapToLong(JudgmentRecord::durationMs).average().orElse(0),
                reportedJudgeCost, judgeCostIncomplete);
        return new EvaluationMetrics(bundle.dataset().cases().size(), bundle.plan().repetitions(),
                List.copyOf(variants), List.copyOf(comparisons), List.copyOf(checkMetrics), judgments.size(), invalid,
                judgeMetric, reportedTotalCost, totalIncomplete);
    }

    private int eligiblePairs(List<AnswerResult> answers, EvaluationPlan.ComparisonSpec comparison) {
        Map<String, AnswerResult> left = new LinkedHashMap<>();
        answers.stream().filter(value -> value.variantId().equals(comparison.left()))
                .forEach(value -> left.put(value.caseId() + ":" + value.repetition(), value));
        int eligible = 0;
        for (AnswerResult right : answers.stream().filter(value -> value.variantId().equals(comparison.right())).toList()) {
            AnswerResult l = left.get(right.caseId() + ":" + right.repetition());
            if (judgeable(l) && judgeable(right)) eligible++;
        }
        return eligible;
    }
    private boolean judgeable(AnswerResult value) { return value != null && !value.answer().isBlank()
            && (value.status() == AnswerResult.AnswerStatus.COMPLETED || value.status() == AnswerResult.AnswerStatus.PARTIAL); }
    private int count(List<AnswerResult> values, AnswerResult.AnswerStatus status) { return (int) values.stream().filter(value -> value.status() == status).count(); }
    private int checkCount(List<CheckResult> values, CheckResult.Status status) { return (int) values.stream().filter(value -> value.status() == status).count(); }
    private double averageDuration(List<AnswerResult> values) { return values.stream().mapToLong(AnswerResult::durationMs).average().orElse(0); }
    private double ratio(int numerator, int denominator) { return denominator == 0 ? 0 : numerator / (double) denominator; }
    private double round(double value) { return Math.round(value * 1_000_000.0) / 1_000_000.0; }
}
