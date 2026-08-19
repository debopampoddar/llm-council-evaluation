package com.debopam.llmcouncil.evaluation.reporting;

import com.debopam.llmcouncil.evaluation.domain.AnswerResult;
import com.debopam.llmcouncil.evaluation.domain.CheckResult;
import com.debopam.llmcouncil.evaluation.domain.EvaluationBundle;
import com.debopam.llmcouncil.evaluation.domain.JudgmentRecord;
import com.debopam.llmcouncil.evaluation.domain.RunManifest;
import com.debopam.llmcouncil.evaluation.statistics.EvaluationMetrics;
import com.debopam.llmcouncil.evaluation.statistics.MetricCalculator;
import com.debopam.llmcouncil.evaluation.storage.EvaluationRunStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.debopam.llmcouncil.evaluation.judging.HumanReviewExporter;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Produces auditable machine-readable metrics and a direct Markdown assessment. */
@Component
public class ReportGenerator {
    private final EvaluationRunStore store;
    private final MetricCalculator calculator;
    private final JudgeIndependenceAnalyzer independence;

    public ReportGenerator(EvaluationRunStore store, MetricCalculator calculator,
                           JudgeIndependenceAnalyzer independence) {
        this.store = store;
        this.calculator = calculator;
        this.independence = independence;
    }

    public EvaluationMetrics generate(Path runDirectory, RunManifest manifest,
                                      EvaluationBundle bundle, JsonNode catalog) {
        List<AnswerResult> answers = store.answers(runDirectory);
        List<CheckResult> checks = store.checks(runDirectory);
        List<JudgmentRecord> judgments = store.judgments(runDirectory);
        EvaluationMetrics metrics = calculator.calculate(bundle, answers, checks, judgments);
        List<JudgeIndependenceAnalyzer.Assessment> assessments = independence.assess(bundle.plan(), catalog);
        List<HumanReviewExporter.NormalizedHumanDecision> human = store
                .readArtifact(runDirectory, "human/human-review-normalized.json",
                        HumanReviewExporter.NormalizedHumanDecision[].class)
                .map(List::of).orElse(List.of());
        store.writeArtifact(runDirectory, "report/metrics.json", metrics);
        store.writeArtifact(runDirectory, "report/judge-independence.json", assessments);
        store.writeReport(runDirectory, "report/metrics.csv", csv(metrics));
        store.writeReport(runDirectory, "report/report.md", markdown(manifest, metrics, assessments, human));
        return metrics;
    }

    private String markdown(RunManifest manifest, EvaluationMetrics metrics,
                            List<JudgeIndependenceAnalyzer.Assessment> assessments,
                            List<HumanReviewExporter.NormalizedHumanDecision> human) {
        StringBuilder out = new StringBuilder("# Evaluation Report: ").append(manifest.plan().id()).append("\n\n");
        int expectedAnswers = metrics.datasetCases() * metrics.repetitions()
                * (int) manifest.plan().variants().stream().filter(value -> !Boolean.FALSE.equals(value.enabled())).count();
        int recordedAnswers = metrics.variants().stream().mapToInt(EvaluationMetrics.VariantMetric::attempts).sum();
        if (recordedAnswers < expectedAnswers) {
            out.append("> **Incomplete run.** Only ").append(recordedAnswers).append(" of ")
                    .append(expectedAnswers).append(" expected candidate attempts are present.\n\n");
        }
        if (metrics.datasetCases() < 30) {
            out.append("> **Pilot only.** This run has fewer than 30 cases and does not support a general quality claim.\n\n");
        }
        out.append("## Run validity\n\n")
                .append("- Run: `").append(manifest.runId()).append("`\n")
                .append("- Created: ").append(manifest.createdAt()).append("\n")
                .append("- Dataset: `").append(manifest.dataset().id()).append("` (`")
                .append(manifest.datasetSha256()).append("`)\n")
                .append("- Rubric: `").append(manifest.rubric().id()).append("` (`")
                .append(manifest.rubricSha256()).append("`)\n")
                .append("- Source commit: `").append(manifest.gitCommit()).append("`")
                .append(manifest.gitDirty() ? " — **dirty worktree**" : " — clean worktree").append("\n")
                .append("- Prompt versions: `").append(manifest.directPromptVersion()).append("`, `")
                .append(manifest.ensemblePromptVersion()).append("`, `")
                .append(manifest.judgePromptVersion()).append("`\n")
                .append("- Cases × repetitions: ").append(metrics.datasetCases()).append(" × ")
                .append(metrics.repetitions()).append("\n");
        if (manifest.runtimeEnvironment() != null) {
            out.append("- Candidate concurrency: ")
                    .append(manifest.runtimeEnvironment().candidateConcurrency()).append("\n")
                    .append("- Judgment concurrency: ")
                    .append(manifest.runtimeEnvironment().judgmentConcurrency()).append("\n")
                    .append("- Declared `OLLAMA_NUM_PARALLEL`: ")
                    .append(manifest.runtimeEnvironment().ollamaNumParallel() == null
                            ? "not exported to the harness"
                            : manifest.runtimeEnvironment().ollamaNumParallel())
                    .append("\n");
        }
        manifest.plan().comparisons().stream()
                .filter(value -> !Boolean.FALSE.equals(value.enabled()) && Boolean.TRUE.equals(value.primary()))
                .findFirst()
                .ifPresent(value -> out.append("- Preregistered primary comparison: `")
                        .append(value.id()).append("` (`").append(value.left()).append("` vs `")
                        .append(value.right()).append("`)\n"));
        out.append("\n");

        out.append("## Reliability and efficiency\n\n")
                .append("| Variant | Attempts | Completed | Partial | Failed | Answer rate | Avg calls | Avg tokens | Avg latency | Cost |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (EvaluationMetrics.VariantMetric value : metrics.variants()) {
            out.append("| ").append(value.variantId()).append(" | ").append(value.attempts()).append(" | ")
                    .append(value.completed()).append(" | ").append(value.partial()).append(" | ")
                    .append(value.failed()).append(" | ").append(percent(value.answerRate())).append(" | ")
                    .append(decimal(value.averageCalls())).append(" | ").append(decimal(value.averageTokens())).append(" | ")
                    .append(duration(value.averageDurationMs())).append(" | ").append(cost(value.totalEstimatedCostUsd(), value.costIncomplete())).append(" |\n");
        }
        out.append("\nJudge usage: ").append(metrics.judgeUsage().calls()).append(" calls, ")
                .append(metrics.judgeUsage().tokens()).append(" tokens, average ")
                .append(duration(metrics.judgeUsage().averageDurationMs())).append(", ")
                .append(cost(metrics.judgeUsage().totalEstimatedCostUsd(), metrics.judgeUsage().costIncomplete()))
                .append(" estimated cost.\n\n")
                .append("**Total recorded estimated cost:** ")
                .append(cost(metrics.totalEstimatedCostUsd(), metrics.totalCostIncomplete())).append("\n\n")
                .append("A `+` suffix means the shown cost is a known subtotal with unpriced or missing usage.\n");

        out.append("\n## Blind pairwise quality\n\n")
                .append("| Comparison | Eligible / intended | Resolved | Left wins | Right wins | Ties | Position unstable | Judge disagreement | Invalid | Missing | Left preference (Wilson 95% CI) | Unresolved sensitivity |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (EvaluationMetrics.ComparisonMetric value : metrics.comparisons()) {
            out.append("| ").append(value.leftVariant()).append(" vs ").append(value.rightVariant()).append(" | ")
                    .append(value.eligiblePairs()).append(" / ").append(value.intendedPairs()).append(" | ")
                    .append(value.judgedPairs()).append(" | ").append(value.leftWins()).append(" | ")
                    .append(value.rightWins()).append(" | ").append(value.ties()).append(" | ")
                    .append(value.positionUnstable()).append(" | ").append(value.judgeDisagreement()).append(" | ")
                    .append(value.invalid()).append(" | ").append(value.missing()).append(" | ")
                    .append(interval(value)).append(" | ").append(sensitivity(value)).append(" |\n");
        }
        out.append("\nThe Wilson interval is conditional on resolved cases. The unresolved-sensitivity range assigns every unresolved eligible pair first against, then in favour of, the left variant. Operational failures are not dropped: the eligible/intended gap is reported separately.\n\n");

        out.append("## Judge independence\n\n| Comparison | Judge | Assessment | Detail |\n|---|---|---|---|\n");
        for (JudgeIndependenceAnalyzer.Assessment value : assessments) {
            out.append("| ").append(value.comparisonId()).append(" | ").append(value.judgeId()).append(" | ")
                    .append(value.tier()).append(" | ").append(value.detail()).append(" |\n");
        }
        out.append("\n## Blinded human review\n\n");
        if (human.isEmpty()) {
            out.append("No human decisions have been imported. Fill a decision file and run `import-human`.\n\n");
        } else {
            long ties = human.stream().filter(value -> value.winner() == HumanReviewExporter.HumanWinner.TIE).count();
            out.append("Imported decisions: ").append(human.size()).append("; ties: ").append(ties)
                    .append(". Human outcomes are reported separately and are not silently blended with model judges.\n\n")
                    .append("| Pair | Winner variant | Rationale |\n|---|---|---|\n");
            human.forEach(value -> out.append("| ").append(value.pairId()).append(" | ")
                    .append(value.winnerVariant() == null ? "TIE" : value.winnerVariant()).append(" | ")
                    .append(value.rationale().replace("|", "\\|").replace("\n", " ")).append(" |\n"));
        }
        out.append("\n## Deterministic checks\n\n| Variant | Pass | Fail | Error |\n|---|---:|---:|---:|\n");
        for (EvaluationMetrics.CheckMetric value : metrics.deterministicChecks()) {
            out.append("| ").append(value.variantId()).append(" | ").append(value.passed()).append(" | ")
                    .append(value.failed()).append(" | ").append(value.errors()).append(" |\n");
        }
        out.append("\n## Limitations\n\n")
                .append("- Model generations are stochastic; the seed controls blinding, not provider generation.\n")
                .append("- LLM judges can exhibit position, verbosity, and family-preference bias; mirrored order and independence labels expose but do not eliminate it.\n")
                .append("- Council self-scores and validation are retained as evidence but are not the primary quality outcome.\n")
                .append("- Direct and council variants use different orchestration prompt templates; align models and generation settings when isolating protocol effects.\n")
                .append("- Council call estimates use the advertised protocol topology; provider retries internal to llm-council may not be exposed by its result API.\n")
                .append("- The cost ceiling is an observed post-call guard, not a prepaid reservation; unpriced calls are excluded and one completed call can cross the threshold.\n")
                .append("- Judge responses still invalid after bounded retries remain invalid evidence and are never converted to ties.\n");
        return out.toString();
    }

    private String csv(EvaluationMetrics metrics) {
        StringBuilder out = new StringBuilder("type,id,attempts,completed,partial,failed,answer_rate,avg_calls,avg_tokens,cost,left_wins,right_wins,ties,unresolved,position_unstable,judge_disagreement,invalid,missing,preference,lower95,upper95,unresolved_lower,unresolved_upper\n");
        for (EvaluationMetrics.VariantMetric v : metrics.variants()) {
            out.append("variant,").append(v.variantId()).append(',').append(v.attempts()).append(',')
                    .append(v.completed()).append(',').append(v.partial()).append(',').append(v.failed()).append(',')
                    .append(v.answerRate()).append(',').append(v.averageCalls()).append(',').append(v.averageTokens()).append(',')
                    .append(v.totalEstimatedCostUsd() == null ? "" : v.totalEstimatedCostUsd()).append(",,,,,,,,,,,,,\n");
        }
        for (EvaluationMetrics.ComparisonMetric c : metrics.comparisons()) {
            out.append("comparison,").append(c.comparisonId()).append(",,,,,,,,,")
                    .append(c.leftWins()).append(',').append(c.rightWins()).append(',').append(c.ties()).append(',')
                    .append(c.unresolved()).append(',').append(c.positionUnstable()).append(',')
                    .append(c.judgeDisagreement()).append(',').append(c.invalid()).append(',').append(c.missing()).append(',')
                    .append(nullable(c.tieAdjustedLeftPreference())).append(',').append(nullable(c.lower95())).append(',')
                    .append(nullable(c.upper95())).append(',').append(nullable(c.unresolvedLowerBound())).append(',')
                    .append(nullable(c.unresolvedUpperBound())).append('\n');
        }
        EvaluationMetrics.JudgeMetric j = metrics.judgeUsage();
        out.append("judge,all,").append(j.records()).append(",,,,,")
                .append(j.calls()).append(',').append(j.tokens()).append(',')
                .append(j.totalEstimatedCostUsd() == null ? "" : j.totalEstimatedCostUsd()).append(",,,,,,,,,,,,,\n");
        out.append("run,total,,,,,,,,")
                .append(metrics.totalEstimatedCostUsd() == null ? "" : metrics.totalEstimatedCostUsd())
                .append(",,,,,,,,,,,,,\n");
        return out.toString();
    }

    private String interval(EvaluationMetrics.ComparisonMetric value) {
        if (value.tieAdjustedLeftPreference() == null) return "—";
        return percent(value.tieAdjustedLeftPreference()) + " (" + percent(value.lower95()) + "–" + percent(value.upper95()) + ")";
    }
    private String sensitivity(EvaluationMetrics.ComparisonMetric value) {
        if (value.unresolvedLowerBound() == null) return "—";
        return percent(value.unresolvedLowerBound()) + "–" + percent(value.unresolvedUpperBound());
    }
    private String percent(double value) { return String.format(Locale.ROOT, "%.1f%%", value * 100); }
    private String decimal(double value) { return String.format(Locale.ROOT, "%.1f", value); }
    private String duration(double millis) { return millis < 1000 ? decimal(millis) + " ms" : decimal(millis / 1000) + " s"; }
    private String cost(Double value, boolean incomplete) { return value == null ? "—" : String.format(Locale.ROOT, "$%.6f%s", value, incomplete ? "+" : ""); }
    private String nullable(Double value) { return value == null ? "" : value.toString(); }
}
