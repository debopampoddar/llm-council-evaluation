package com.debopam.llmcouncil.evaluation.execution;

import com.debopam.llmcouncil.evaluation.checks.DeterministicCheckEngine;
import com.debopam.llmcouncil.evaluation.config.EvaluationInputLoader;
import com.debopam.llmcouncil.evaluation.council.CouncilApiGateway;
import com.debopam.llmcouncil.evaluation.council.CouncilCallEstimator;
import com.debopam.llmcouncil.evaluation.domain.AnswerResult;
import com.debopam.llmcouncil.evaluation.domain.EvaluationBundle;
import com.debopam.llmcouncil.evaluation.domain.EvaluationDataset;
import com.debopam.llmcouncil.evaluation.domain.EvaluationPlan;
import com.debopam.llmcouncil.evaluation.domain.JudgePreflightResult;
import com.debopam.llmcouncil.evaluation.domain.JudgmentRecord;
import com.debopam.llmcouncil.evaluation.domain.RunManifest;
import com.debopam.llmcouncil.evaluation.domain.RuntimeEnvironment;
import com.debopam.llmcouncil.evaluation.domain.UsageMetrics;
import com.debopam.llmcouncil.evaluation.judging.BlindOrder;
import com.debopam.llmcouncil.evaluation.judging.HumanReviewExporter;
import com.debopam.llmcouncil.evaluation.judging.PairwiseJudgingService;
import com.debopam.llmcouncil.evaluation.reporting.ReportGenerator;
import com.debopam.llmcouncil.evaluation.statistics.EvaluationMetrics;
import com.debopam.llmcouncil.evaluation.storage.EvaluationRunStore;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/**
 * Resumable experiment orchestration.
 *
 * <p>All candidate execution stays sequential so latency and reliability are
 * measured under comparable resource conditions. Independent judgments may use
 * bounded concurrency after candidate evidence is complete.
 */
@Component
public class EvaluationRunner {
    private final CouncilApiGateway council;
    private final CouncilCallEstimator estimator;
    private final AnswerGenerator answers;
    private final DeterministicCheckEngine checks;
    private final PairwiseJudgingService judging;
    private final HumanReviewExporter humanReview;
    private final EvaluationRunStore store;
    private final EvaluationInputLoader loader;
    private final ReportGenerator reports;
    private final ProgressReporter progress;
    private final UnitExecutor units;

    @Autowired
    public EvaluationRunner(CouncilApiGateway council, CouncilCallEstimator estimator,
                            AnswerGenerator answers, DeterministicCheckEngine checks,
                            PairwiseJudgingService judging, HumanReviewExporter humanReview,
                            EvaluationRunStore store, EvaluationInputLoader loader,
                            ReportGenerator reports, ProgressReporter progress) {
        this(council, estimator, answers, checks, judging, humanReview, store, loader,
                reports, progress, UnitExecutor.fromEnvironment());
    }

    EvaluationRunner(CouncilApiGateway council, CouncilCallEstimator estimator,
                     AnswerGenerator answers, DeterministicCheckEngine checks,
                     PairwiseJudgingService judging, HumanReviewExporter humanReview,
                     EvaluationRunStore store, EvaluationInputLoader loader,
                     ReportGenerator reports, ProgressReporter progress, UnitExecutor units) {
        this.council = council;
        this.estimator = estimator;
        this.answers = answers;
        this.checks = checks;
        this.judging = judging;
        this.humanReview = humanReview;
        this.store = store;
        this.loader = loader;
        this.reports = reports;
        this.progress = progress;
        this.units = units;
    }

    public PreparedPlan prepare(EvaluationBundle bundle) {
        progress.phase("Preflight: loading the live council catalog.");
        JsonNode catalog = council.catalog(bundle.plan());
        List<String> warnings = new ArrayList<>();
        List<PlanAssessment.VariantEstimate> estimates = new ArrayList<>();
        long minCalls = 0;
        long maxCalls = 0;
        long answerUnits = 0;
        boolean billable = false;
        boolean hasCouncilVariant = false;
        Set<String> validatedModels = new HashSet<>();
        long units = (long) bundle.dataset().cases().size() * bundle.plan().repetitions();

        for (EvaluationPlan.VariantSpec variant : enabledVariants(bundle.plan())) {
            int min;
            int max;
            String detail;
            if (variant.type() == EvaluationPlan.VariantType.COUNCIL) {
                hasCouncilVariant = true;
                progress.info("Preflight: checking council variant '" + variant.id() + "' ("
                        + variant.profileId() + "/" + variant.depthMode() + ").");
                JsonNode health = council.health(bundle.plan(), variant);
                if (!health.path("runnable").asBoolean(false)) {
                    throw new IllegalStateException("Council profile preflight failed for " + variant.id()
                            + ": " + health.toPrettyString());
                }
                health.path("warnings").forEach(value -> warnings.add(variant.id() + ": " + value.asText()));
                billable |= containsCloudProvider(health);
                CouncilCallEstimator.CallRange range = estimator.estimate(catalog, variant);
                min = range.minimum();
                max = range.maximum();
                detail = range.policyId() + " / " + range.protocolId();
            } else {
                int calls = variant.type() == EvaluationPlan.VariantType.DIRECT ? 1 : variant.samples() + 1;
                min = calls;
                max = calls * (1 + retries(bundle.plan(), variant.modelId()));
                EvaluationPlan.ModelSpec model = model(bundle.plan(), variant.modelId());
                validateOnce(model, validatedModels);
                billable |= cloud(model.provider());
                warnIfUnpriced(model, warnings);
                detail = model.provider() + " / " + model.providerModelId();
            }
            answerUnits += units;
            minCalls += min * units;
            maxCalls += max * units;
            estimates.add(new PlanAssessment.VariantEstimate(variant.id(), units, min, max, detail));
        }
        if (hasCouncilVariant) {
            warnings.add("Council call estimates cover protocol topology; provider retries inside llm-council may add attempts not exposed by its result API.");
        }
        long maxJudgeCalls = 0;
        long comparisons = bundle.plan().comparisons().stream().filter(value -> !Boolean.FALSE.equals(value.enabled())).count();
        if (comparisons == 0) warnings.add("No comparison is enabled; this run will test mechanics only and produce no pairwise quality evidence.");
        if (comparisons > 1 && bundle.plan().comparisons().stream()
                .noneMatch(value -> !Boolean.FALSE.equals(value.enabled()) && Boolean.TRUE.equals(value.primary())))
            warnings.add("Multiple comparisons are enabled but none is preregistered as primary; treat all results as exploratory.");
        if (comparisons > 0 && bundle.plan().repetitions() < 3)
            warnings.add("Fewer than three repetitions are configured; stochastic variation will be weakly characterized.");
        long enabledJudgeCount = enabledJudges(bundle.plan()).size();
        if (comparisons > 0 && enabledJudgeCount == 1)
            warnings.add("Only one judge is enabled; its model-specific bias cannot be outvoted.");
        for (EvaluationPlan.JudgeSpec judge : enabledJudges(bundle.plan())) {
            EvaluationPlan.ModelSpec judgeModel = model(bundle.plan(), judge.modelId());
            validateOnce(judgeModel, validatedModels);
            int orientations = Boolean.TRUE.equals(judge.mirrored()) ? 2 : 1;
            maxJudgeCalls += comparisons * units * orientations
                    * (1 + invalidJudgeRetries(bundle.plan()))
                    * (1 + retries(bundle.plan(), judge.modelId()));
            // One real structured smoke call per judge runs after live/billable
            // confirmation and before any candidate answer is generated.
            maxJudgeCalls += 1L + retries(bundle.plan(), judge.modelId());
            billable |= cloud(judgeModel.provider());
            warnIfUnpriced(judgeModel, warnings);
            if (!Boolean.TRUE.equals(judge.mirrored()))
                warnings.add("Judge '" + judge.id() + "' is not mirrored; position bias will not be measured.");
        }
        if (bundle.dataset().cases().size() < 30) {
            warnings.add("Small-sample limitation: fewer than 30 cases; do not make a general quality claim.");
        }
        PlanAssessment assessment = new PlanAssessment(bundle.dataset().cases().size(), bundle.plan().repetitions(),
                answerUnits, minCalls, maxCalls, maxJudgeCalls, maxCalls + maxJudgeCalls,
                billable, List.copyOf(estimates), List.copyOf(warnings));
        progress.info("Preflight passed: " + answerUnits + " answer units, up to "
                + (maxCalls + maxJudgeCalls) + " estimated protocol calls.");
        return new PreparedPlan(catalog, assessment);
    }

    public RunOutcome runNew(EvaluationInputLoader.LoadedInputs inputs,
                             boolean confirmLive, boolean confirmBillable) {
        PreparedPlan prepared = prepare(inputs.bundle());
        confirm(inputs.bundle().plan(), prepared.assessment(), confirmLive, confirmBillable);
        EvaluationRunStore.RunHandle handle = store.create(inputs.bundle(), inputs.hashes(), prepared.catalog(),
                RuntimeEnvironment.capture(units.concurrency()));
        progress.info("Run directory: " + handle.directory());
        return execute(handle, inputs.bundle(), prepared.catalog());
    }

    public RunOutcome resume(Path runDirectory, boolean confirmLive, boolean confirmBillable) {
        EvaluationRunStore.RunHandle handle = store.open(runDirectory);
        validateRuntimeEnvironment(handle.manifest());
        EvaluationBundle bundle = loader.fromManifest(handle.manifest(), handle.directory());
        PreparedPlan prepared = prepare(bundle);
        String current = store.catalogFingerprint(prepared.catalog());
        if (!current.equals(handle.manifest().councilCatalogSha256())) {
            throw new IllegalStateException("Council catalog changed since this run started. Refusing to mix evidence; start a new run.");
        }
        confirm(bundle.plan(), prepared.assessment(), confirmLive, confirmBillable);
        return execute(handle, bundle, prepared.catalog());
    }

    public EvaluationMetrics report(Path runDirectory) {
        EvaluationRunStore.RunHandle handle = store.open(runDirectory);
        EvaluationBundle bundle = loader.fromManifest(handle.manifest(), handle.directory());
        JsonNode catalog = store.readArtifact(runDirectory, "preflight/catalog.json", JsonNode.class)
                .orElseThrow(() -> new IllegalStateException("Run has no catalog snapshot"));
        return reports.generate(handle.directory(), handle.manifest(), bundle, catalog);
    }

    private RunOutcome execute(EvaluationRunStore.RunHandle handle, EvaluationBundle bundle, JsonNode catalog) {
        Path directory = handle.directory();
        int consumed = store.answers(directory).stream().mapToInt(value -> value.usage().calls()).sum()
                + store.judgments(directory).stream().mapToInt(value -> value.usage().calls()).sum()
                + store.judgePreflights(directory).stream().mapToInt(value -> value.usage().calls()).sum();
        CallBudget budget = new CallBudget(bundle.plan().execution().maxCalls(), consumed);
        Map<String, CouncilCallEstimator.CallRange> councilRanges = new LinkedHashMap<>();
        enabledVariants(bundle.plan()).stream().filter(value -> value.type() == EvaluationPlan.VariantType.COUNCIL)
                .forEach(value -> councilRanges.put(value.id(), estimator.estimate(catalog, value)));

        long totalAnswers = (long) bundle.dataset().cases().size() * bundle.plan().repetitions()
                * enabledVariants(bundle.plan()).size();
        long answerOrdinal = 0;
        try {
            preflightJudges(bundle, directory, budget);
            progress.phase("Candidate generation started.");
            store.state(directory, "RUNNING", "Generating candidate answers.");
            for (EvaluationDataset.EvaluationCase evalCase : bundle.dataset().cases()) {
                for (int repetition = 1; repetition <= bundle.plan().repetitions(); repetition++) {
                    // Candidate calls stay exclusive so per-variant latency and
                    // reliability are measured without cross-variant contention.
                    List<Runnable> councilUnits = new ArrayList<>();
                    List<Runnable> nonCouncilUnits = new ArrayList<>();
                    for (EvaluationPlan.VariantSpec variant : enabledVariants(bundle.plan())) {
                        answerOrdinal++;
                        long ordinal = answerOrdinal;
                        int currentRepetition = repetition;
                        Runnable unit = () -> generateAnswer(bundle, directory, budget, councilRanges,
                                evalCase, variant, currentRepetition, ordinal, totalAnswers);
                        if (variant.type() == EvaluationPlan.VariantType.COUNCIL) councilUnits.add(unit);
                        else nonCouncilUnits.add(unit);
                    }
                    // Council first, and one at a time: it is the riskiest dependency,
                    // so a health, quorum, or catalog problem surfaces on the first case
                    // rather than after every cheap variant has already been paid for.
                    councilUnits.forEach(Runnable::run);
                    nonCouncilUnits.forEach(Runnable::run);
                }
            }

            List<AnswerResult> allAnswers = store.answers(directory);
            humanReview.export(directory, bundle, allAnswers);
            if (expectedJudgments(bundle, answersByKey(allAnswers)) > 0) {
                progress.phase("Blind pairwise judging started.");
                store.state(directory, "RUNNING", "Running blind pairwise judgments.");
                judgeMissing(bundle, directory, allAnswers, budget);
            } else {
                progress.info("Blind pairwise judging skipped: no eligible comparison/judge units.");
            }
            enforceCost(bundle.plan(), directory);
            EvaluationMetrics metrics = reports.generate(directory, handle.manifest(), bundle, catalog);
            store.state(directory, "COMPLETED", "Evaluation and report completed.");
            progress.phase("Evaluation completed. Report: " + directory.resolve("report/report.md"));
            return new RunOutcome(directory, metrics, budget.consumed());
        } catch (RuntimeException ex) {
            store.state(directory, ex instanceof CallBudget.BudgetExceededException ? "BUDGET_EXHAUSTED" : "INTERRUPTED",
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            progress.info("Run stopped with state "
                    + (ex instanceof CallBudget.BudgetExceededException ? "BUDGET_EXHAUSTED" : "INTERRUPTED")
                    + ": " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
            throw ex;
        }
    }

    /**
     * Generate, check, and record one candidate answer.
     *
     * <p>Extracted so it can be handed to {@link UnitExecutor}. Every mutation it
     * performs is safe to overlap: {@link CallBudget} is synchronized, the store
     * writes each unit to its own path through a temporary file and an atomic
     * move, and {@link ProgressReporter} serialises its output.
     *
     * @param evalCase      the case being answered
     * @param variant       the variant producing the answer
     * @param repetition    1-based repetition index
     * @param ordinal       position in the original sequential ordering, for display
     * @param totalAnswers  total answer units, for display
     */
    private void generateAnswer(EvaluationBundle bundle, Path directory, CallBudget budget,
                                Map<String, CouncilCallEstimator.CallRange> councilRanges,
                                EvaluationDataset.EvaluationCase evalCase,
                                EvaluationPlan.VariantSpec variant, int repetition,
                                long ordinal, long totalAnswers) {
        String unit = evalCase.id() + "/" + variant.id() + "/r" + repetition;
        var existing = store.answer(directory, evalCase.id(), variant.id(), repetition);
        if (existing.isPresent()) {
            if (!store.checksPresent(directory, evalCase.id(), variant.id(), repetition)) {
                store.checks(directory, existing.get(), checks.evaluate(evalCase, existing.get()));
                progress.info("Repaired missing deterministic checks for " + unit + ".");
            }
            progress.skipped("ANSWER", ordinal, totalAnswers, unit, "evidence already exists");
            store.state(directory, "RUNNING", "Candidate answers " + ordinal + "/" + totalAnswers + ".");
            return;
        }
        progress.started("ANSWER", ordinal, totalAnswers, unit);
        int upper = upperBound(bundle.plan(), variant, councilRanges);
        CallBudget.Reservation reservation = budget.reserve(upper, unit);
        AnswerResult result = progress.withHeartbeat("answer " + unit,
                () -> answers.generate(bundle.plan(), evalCase, variant, repetition));
        store.answer(directory, result);
        var checkResults = checks.evaluate(evalCase, result);
        store.checks(directory, result, checkResults);
        budget.reconcile(reservation, result.usage().calls());
        long checkFailures = checkResults.stream()
                .filter(value -> value.status() != com.debopam.llmcouncil.evaluation.domain.CheckResult.Status.PASS)
                .count();
        progress.completed("ANSWER", ordinal, totalAnswers, unit,
                result.status() + (checkFailures == 0 ? "" : ", " + checkFailures + " check failures"),
                result.durationMs(), result.usage().calls());
        store.state(directory, "RUNNING", "Candidate answers " + ordinal + "/" + totalAnswers + ".");
        enforceCost(bundle.plan(), directory);
        if (result.status() == AnswerResult.AnswerStatus.FAILED
                && !Boolean.TRUE.equals(bundle.plan().execution().continueOnFailure())) {
            throw new IllegalStateException("Variant failed and continueOnFailure is false: " + result.unitId());
        }
    }

    private void judgeMissing(EvaluationBundle bundle, Path directory, List<AnswerResult> answerList,
                              CallBudget budget) {
        Map<String, AnswerResult> byKey = answersByKey(answerList);
        long totalJudgments = expectedJudgments(bundle, byKey);
        long judgmentOrdinal = 0;
        // Every judgment is independent: no council state, no shared mutable
        // evidence path, and blind order is derived per pair from the plan seed
        // rather than from execution order. Collect them all, then run them with
        // whatever concurrency the environment allows.
        List<Runnable> pending = new ArrayList<>();
        for (EvaluationPlan.ComparisonSpec comparison : bundle.plan().comparisons()) {
            if (Boolean.FALSE.equals(comparison.enabled())) continue;
            for (EvaluationDataset.EvaluationCase evalCase : bundle.dataset().cases()) {
                for (int repetition = 1; repetition <= bundle.plan().repetitions(); repetition++) {
                    AnswerResult left = byKey.get(key(evalCase.id(), comparison.left(), repetition));
                    AnswerResult right = byKey.get(key(evalCase.id(), comparison.right(), repetition));
                    if (!judgeable(left) || !judgeable(right)) continue;
                    String pairId = comparison.id() + ":" + evalCase.id() + ":r" + repetition;
                    boolean leftFirst = BlindOrder.leftFirst(bundle.plan().seed(), pairId);
                    for (EvaluationPlan.JudgeSpec judge : enabledJudges(bundle.plan())) {
                        int count = Boolean.TRUE.equals(judge.mirrored()) ? 2 : 1;
                        for (int orientation = 1; orientation <= count; orientation++) {
                            judgmentOrdinal++;
                            long ordinal = judgmentOrdinal;
                            int currentOrientation = orientation;
                            int currentRepetition = repetition;
                            pending.add(() -> judgeOne(bundle, directory, budget, comparison, evalCase,
                                    currentRepetition, judge, currentOrientation, left, right, leftFirst,
                                    pairId, ordinal, totalJudgments));
                        }
                    }
                }
            }
        }
        units.run(pending);
    }

    /**
     * Run one judge orientation for one pair, with its invalid-response retries.
     *
     * <p>Extracted so it can be handed to {@link UnitExecutor}. Retries stay
     * inside the unit, so a retried judgment never interleaves its attempts with
     * another unit's.
     *
     * @param leftFirst  seeded blind assignment for this pair
     * @param ordinal    position in the original sequential ordering, for display
     */
    private void judgeOne(EvaluationBundle bundle, Path directory, CallBudget budget,
                          EvaluationPlan.ComparisonSpec comparison,
                          EvaluationDataset.EvaluationCase evalCase, int repetition,
                          EvaluationPlan.JudgeSpec judge, int orientation,
                          AnswerResult left, AnswerResult right, boolean leftFirst,
                          String pairId, long ordinal, long totalJudgments) {
        String unit = pairId + "/" + judge.id() + "/o" + orientation;
        if (store.judgment(directory, comparison.id(), evalCase.id(), repetition,
                judge.id(), orientation).isPresent()) {
            progress.skipped("JUDGE", ordinal, totalJudgments, unit, "evidence already exists");
            store.state(directory, "RUNNING", "Judgments " + ordinal + "/" + totalJudgments + ".");
            return;
        }
        progress.started("JUDGE", ordinal, totalJudgments, unit);
        boolean normal = orientation == 1 ? leftFirst : !leftFirst;
        AnswerResult a = normal ? left : right;
        AnswerResult b = normal ? right : left;
        int upper = (1 + invalidJudgeRetries(bundle.plan()))
                * (1 + retries(bundle.plan(), judge.modelId()));
        CallBudget.Reservation reservation = budget.reserve(upper, unit);
        JudgmentRecord record = null;
        UsageMetrics combinedUsage = UsageMetrics.empty();
        long combinedDuration = 0;
        int maximumAttempts = 1 + invalidJudgeRetries(bundle.plan());
        for (int attempt = 1; attempt <= maximumAttempts; attempt++) {
            int currentAttempt = attempt;
            JudgmentRecord current = progress.withHeartbeat(
                    "judgment " + unit + " attempt " + currentAttempt + "/" + maximumAttempts,
                    () -> judging.judge(bundle.plan(), bundle.rubric(), evalCase,
                            comparison, judge, a, b, orientation));
            store.judgmentAttempt(directory, current, attempt);
            combinedUsage = combinedUsage.plus(current.usage());
            combinedDuration += current.durationMs();
            record = current;
            if (current.status() != JudgmentRecord.Status.INVALID || attempt == maximumAttempts) {
                break;
            }
            progress.info("Judge output was invalid for " + unit + " ("
                    + current.failureReason() + "); retrying with a fresh model call.");
        }
        record = withTotals(record, combinedDuration, combinedUsage);
        store.judgment(directory, record);
        budget.reconcile(reservation, record.usage().calls());
        progress.completed("JUDGE", ordinal, totalJudgments, unit,
                record.status().toString(), record.durationMs(), record.usage().calls());
        store.state(directory, "RUNNING", "Judgments " + ordinal + "/" + totalJudgments + ".");
        enforceCost(bundle.plan(), directory);
    }

    private void preflightJudges(EvaluationBundle bundle, Path directory, CallBudget budget) {
        if (bundle.plan().comparisons().stream()
                .noneMatch(value -> !Boolean.FALSE.equals(value.enabled()))) {
            return;
        }
        progress.phase("Judge smoke preflight started.");
        store.state(directory, "RUNNING", "Validating structured judge output.");
        for (EvaluationPlan.JudgeSpec judge : enabledJudges(bundle.plan())) {
            var existing = store.judgePreflight(directory, judge.id());
            if (existing.isPresent()
                    && existing.get().status() == JudgePreflightResult.Status.PASSED) {
                progress.info("Judge preflight already passed for '" + judge.id() + "'.");
                continue;
            }
            int upper = 1 + retries(bundle.plan(), judge.modelId());
            CallBudget.Reservation reservation = budget.reserve(upper,
                    "judge-preflight/" + judge.id());
            progress.info("Judge preflight: requesting structured control judgment from '"
                    + judge.id() + "'.");
            JudgePreflightResult result = progress.withHeartbeat("judge preflight " + judge.id(),
                    () -> judging.preflight(bundle.plan(), bundle.rubric(), judge));
            store.judgePreflight(directory, result);
            budget.reconcile(reservation, result.usage().calls());
            if (result.status() != JudgePreflightResult.Status.PASSED) {
                throw new IllegalStateException("Judge preflight failed for '" + judge.id() + "' ["
                        + result.failureCategory() + "]: " + result.failureReason());
            }
            progress.info("Judge preflight passed for '" + judge.id() + "' in "
                    + result.durationMs() + " ms.");
        }
    }

    private void confirm(EvaluationPlan plan, PlanAssessment assessment,
                         boolean confirmLive, boolean confirmBillable) {
        if (!confirmLive || !Boolean.TRUE.equals(plan.execution().liveCallsAcknowledged())) {
            throw new IllegalStateException("Live evaluation calls were not acknowledged. Use --confirm-live and set execution.liveCallsAcknowledged: true.");
        }
        if (assessment.billableProviders()
                && (!confirmBillable || !Boolean.TRUE.equals(plan.execution().billableCallsAcknowledged()))) {
            throw new IllegalStateException("Potentially billable providers require --confirm-billable and execution.billableCallsAcknowledged: true.");
        }
        if (assessment.maximumTotalCalls() > plan.execution().maxCalls()) {
            throw new IllegalStateException("Plan worst-case call estimate " + assessment.maximumTotalCalls()
                    + " exceeds execution.maxCalls " + plan.execution().maxCalls());
        }
    }

    private void validateRuntimeEnvironment(RunManifest manifest) {
        RuntimeEnvironment recorded = manifest.runtimeEnvironment();
        RuntimeEnvironment current = RuntimeEnvironment.capture(units.concurrency());
        if (recorded == null) {
            progress.info("Legacy run manifest has no runtime execution metadata; "
                    + "resume is allowed for compatibility, but execution conditions cannot be verified.");
            return;
        }
        if (!recorded.equals(current)) {
            throw new IllegalStateException("Runtime execution settings changed since this run started "
                    + "(recorded=" + recorded + ", current=" + current
                    + "). Refusing to mix evidence; restore the original settings or start a new run.");
        }
    }

    private void enforceCost(EvaluationPlan plan, Path directory) {
        Double maximum = plan.execution().maxEstimatedCostUsd();
        if (maximum == null) return;
        double actual = store.answers(directory).stream().filter(value -> value.usage().estimatedCostUsd() != null)
                .mapToDouble(value -> value.usage().estimatedCostUsd()).sum()
                + store.judgments(directory).stream().filter(value -> value.usage().estimatedCostUsd() != null)
                .mapToDouble(value -> value.usage().estimatedCostUsd()).sum()
                + store.judgePreflights(directory).stream()
                .filter(value -> value.usage().estimatedCostUsd() != null)
                .mapToDouble(value -> value.usage().estimatedCostUsd()).sum();
        if (actual > maximum) throw new CallBudget.BudgetExceededException(
                "Estimated cost $" + actual + " exceeded configured maximum $" + maximum);
    }

    private int upperBound(EvaluationPlan plan, EvaluationPlan.VariantSpec variant,
                           Map<String, CouncilCallEstimator.CallRange> ranges) {
        if (variant.type() == EvaluationPlan.VariantType.COUNCIL) return ranges.get(variant.id()).maximum();
        int calls = variant.type() == EvaluationPlan.VariantType.DIRECT ? 1 : variant.samples() + 1;
        return calls * (1 + retries(plan, variant.modelId()));
    }
    private int retries(EvaluationPlan plan, String modelId) { Integer value = model(plan, modelId).retryMaxAttempts(); return value == null ? 1 : value; }
    private int invalidJudgeRetries(EvaluationPlan plan) {
        Integer value = plan.execution().judgeInvalidRetries();
        return value == null ? 1 : value;
    }
    private JudgmentRecord withTotals(JudgmentRecord record, long duration, UsageMetrics usage) {
        return new JudgmentRecord(record.judgmentId(), record.pairId(), record.comparisonId(), record.caseId(),
                record.repetition(), record.judgeId(), record.orientation(), record.answerAVariant(),
                record.answerBVariant(), record.status(), record.winner(), record.confidence(), record.scoresA(),
                record.scoresB(), record.violationsA(), record.violationsB(), record.rationale(),
                record.rawResponse(), record.failureReason(), record.completedAt(), duration, usage);
    }
    private EvaluationPlan.ModelSpec model(EvaluationPlan plan, String id) { return plan.models().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow(); }
    private List<EvaluationPlan.VariantSpec> enabledVariants(EvaluationPlan plan) { return plan.variants().stream().filter(value -> !Boolean.FALSE.equals(value.enabled())).toList(); }
    private List<EvaluationPlan.JudgeSpec> enabledJudges(EvaluationPlan plan) { return plan.judges().stream().filter(value -> !Boolean.FALSE.equals(value.enabled())).toList(); }
    private boolean judgeable(AnswerResult value) { return value != null && !value.answer().isBlank()
            && (value.status() == AnswerResult.AnswerStatus.COMPLETED || value.status() == AnswerResult.AnswerStatus.PARTIAL); }
    private Map<String, AnswerResult> answersByKey(List<AnswerResult> answers) {
        Map<String, AnswerResult> byKey = new LinkedHashMap<>();
        answers.forEach(value -> byKey.put(key(value.caseId(), value.variantId(), value.repetition()), value));
        return byKey;
    }
    private String key(String caseId, String variantId, int repetition) { return caseId + ":" + variantId + ":" + repetition; }
    private boolean cloud(String provider) { return !"ollama".equalsIgnoreCase(provider) && !"mock".equalsIgnoreCase(provider); }
    private boolean containsCloudProvider(JsonNode health) { for (JsonNode model : health.path("models")) if (cloud(model.path("provider").asText())) return true; return false; }
    private void warnIfUnpriced(EvaluationPlan.ModelSpec model, List<String> warnings) {
        if (cloud(model.provider()) && value(model.costPer1kInputTokens()) == 0 && value(model.costPer1kOutputTokens()) == 0) {
            String warning = "Cloud model '" + model.id() + "' is unpriced; cost totals and maxEstimatedCostUsd cannot cover it.";
            if (!warnings.contains(warning)) warnings.add(warning);
        }
    }
    private double value(Double value) { return value == null ? 0 : value; }
    private void validateOnce(EvaluationPlan.ModelSpec model, Set<String> validated) {
        if (validated.add(model.id())) {
            progress.info("Preflight: validating model '" + model.id() + "' ("
                    + model.provider() + "/" + model.providerModelId() + ").");
            answers.validateModel(model);
        }
    }
    private long expectedJudgments(EvaluationBundle bundle, Map<String, AnswerResult> byKey) {
        long total = 0;
        for (EvaluationPlan.ComparisonSpec comparison : bundle.plan().comparisons()) {
            if (Boolean.FALSE.equals(comparison.enabled())) continue;
            for (EvaluationDataset.EvaluationCase evalCase : bundle.dataset().cases()) {
                for (int repetition = 1; repetition <= bundle.plan().repetitions(); repetition++) {
                    if (!judgeable(byKey.get(key(evalCase.id(), comparison.left(), repetition)))
                            || !judgeable(byKey.get(key(evalCase.id(), comparison.right(), repetition)))) continue;
                    for (EvaluationPlan.JudgeSpec judge : enabledJudges(bundle.plan()))
                        total += Boolean.TRUE.equals(judge.mirrored()) ? 2 : 1;
                }
            }
        }
        return total;
    }

    public record PreparedPlan(JsonNode catalog, PlanAssessment assessment) {}
    public record RunOutcome(Path runDirectory, EvaluationMetrics metrics, int callsConsumed) {}
}
