package com.debopam.llmcouncil.evaluation.execution;

import com.debopam.llmcouncil.evaluation.TestFixtures;
import com.debopam.llmcouncil.evaluation.checks.DeterministicCheckEngine;
import com.debopam.llmcouncil.evaluation.config.EvaluationInputLoader;
import com.debopam.llmcouncil.evaluation.config.EvaluationInputValidator;
import com.debopam.llmcouncil.evaluation.config.InputHashes;
import com.debopam.llmcouncil.evaluation.council.CouncilApiGateway;
import com.debopam.llmcouncil.evaluation.council.CouncilCallEstimator;
import com.debopam.llmcouncil.evaluation.domain.EvaluationDataset;
import com.debopam.llmcouncil.evaluation.domain.EvaluationPlan;
import com.debopam.llmcouncil.evaluation.domain.UsageMetrics;
import com.debopam.llmcouncil.evaluation.judging.HumanReviewExporter;
import com.debopam.llmcouncil.evaluation.judging.JudgePromptFactory;
import com.debopam.llmcouncil.evaluation.judging.JudgeResponseParser;
import com.debopam.llmcouncil.evaluation.judging.PairwiseJudgingService;
import com.debopam.llmcouncil.evaluation.model.ModelPrompt;
import com.debopam.llmcouncil.evaluation.model.ModelResponse;
import com.debopam.llmcouncil.evaluation.reporting.JudgeIndependenceAnalyzer;
import com.debopam.llmcouncil.evaluation.reporting.ReportGenerator;
import com.debopam.llmcouncil.evaluation.statistics.JudgmentAggregator;
import com.debopam.llmcouncil.evaluation.statistics.MetricCalculator;
import com.debopam.llmcouncil.evaluation.storage.EvaluationRunStore;
import com.debopam.llmcouncil.evaluation.storage.GitFingerprint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationRunnerIntegrationTest {
    @TempDir Path temp;

    @Test
    void completesFullRunAndResumeDoesNotRepeatCompletedUnits() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AtomicInteger councilRuns = new AtomicInteger();
        AtomicInteger judgeCalls = new AtomicInteger();
        CouncilApiGateway council = new CouncilApiGateway() {
            @Override public JsonNode catalog(EvaluationPlan plan) { return TestFixtures.catalog(); }
            @Override public JsonNode health(EvaluationPlan plan, EvaluationPlan.VariantSpec variant) {
                return tree(mapper, "{\"runnable\":true,\"models\":[{\"provider\":\"ollama\"}],\"warnings\":[]}");
            }
            @Override public JsonNode run(EvaluationPlan plan, EvaluationPlan.VariantSpec variant,
                                          EvaluationDataset.EvaluationCase evalCase) {
                councilRuns.incrementAndGet();
                return tree(mapper, "{\"status\":\"COMPLETED\",\"answer\":\"The answer is four.\",\"warnings\":[],\"usage\":{\"calls\":6,\"promptTokens\":60,\"completionTokens\":20,\"estimatedCostUsd\":0.0,\"estimated\":false,\"partiallyPriced\":false}}");
            }
        };
        var modelProvider = (com.debopam.llmcouncil.evaluation.model.ModelGatewayProvider) model ->
                prompt -> response(prompt, judgeCalls);
        var validator = new EvaluationInputValidator();
        var loader = new EvaluationInputLoader(validator);
        var store = new EvaluationRunStore(mapper, new GitFingerprint());
        var promptFactory = new EvaluationPromptFactory();
        var answerGenerator = new AnswerGenerator(council, modelProvider, promptFactory);
        var judge = new PairwiseJudgingService(modelProvider, new JudgePromptFactory(), new JudgeResponseParser(mapper));
        var human = new HumanReviewExporter(store, mapper);
        var calculator = new MetricCalculator(new JudgmentAggregator());
        var report = new ReportGenerator(store, calculator, new JudgeIndependenceAnalyzer());
        var runner = new EvaluationRunner(council, new CouncilCallEstimator(), answerGenerator,
                new DeterministicCheckEngine(), judge, human, store, loader, report,
                new ProgressReporter(false, 30));

        var bundle = TestFixtures.bundle(temp);
        var loaded = new EvaluationInputLoader.LoadedInputs(bundle,
                new InputHashes("a".repeat(64), "b".repeat(64), "c".repeat(64)));
        EvaluationRunner.RunOutcome first = runner.runNew(loaded, true, false);

        assertEquals(1, councilRuns.get());
        assertEquals(2, store.answers(first.runDirectory()).size());
        assertEquals(2, store.judgments(first.runDirectory()).size());
        assertEquals(3, judgeCalls.get(), "one invalid judge response must be retried exactly once");
        assertTrue(Files.isRegularFile(first.runDirectory().resolve(
                "judgment-attempts/direct-vs-council/case-1/r01/judge/o1/attempt-1.json")));
        assertTrue(Files.isRegularFile(first.runDirectory().resolve(
                "judgment-attempts/direct-vs-council/case-1/r01/judge/o1/attempt-2.json")));
        assertTrue(Files.isRegularFile(first.runDirectory().resolve("report/report.md")));
        assertTrue(Files.isRegularFile(first.runDirectory().resolve("human/human-review-template.json")));

        Path checkFile = first.runDirectory().resolve("checks/case-1/direct/r01.json");
        try { Files.delete(checkFile); }
        catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
        runner.resume(first.runDirectory(), true, false);
        assertEquals(1, councilRuns.get(), "resume must not re-run completed council units");
        assertEquals(2, store.judgments(first.runDirectory()).size());
        assertEquals(3, judgeCalls.get(), "resume must not repeat completed judge attempts");
        assertTrue(Files.isRegularFile(checkFile), "resume must repair checks missing after an interrupted atomic answer write");
    }

    private ModelResponse response(ModelPrompt prompt, AtomicInteger judgeCalls) {
        String text;
        if (prompt.system().contains("blind pairwise evaluator")) {
            text = judgeCalls.incrementAndGet() == 1
                    ? "{\"winner\":\"TIE\",\"confidence\":0.8,\"scores\":{\"A\":{\"criterion-id\":90},\"B\":{\"criterion-id\":90}},\"violations\":{\"A\":[],\"B\":[]},\"rationale\":\"Malformed criterion keys.\"}"
                    : "{\"winner\":\"TIE\",\"confidence\":0.8,\"scores\":{\"A\":{\"correctness\":90,\"clarity\":90},\"B\":{\"correctness\":90,\"clarity\":90}},\"violations\":{\"A\":[],\"B\":[]},\"rationale\":\"Both are correct.\"}";
        } else {
            text = "The answer is four.";
        }
        return new ModelResponse(text, 1, new UsageMetrics(1, 10, 5, 0.0, false, false));
    }

    private JsonNode tree(ObjectMapper mapper, String json) {
        try { return mapper.readTree(json); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
