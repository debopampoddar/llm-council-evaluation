package com.debopam.llmcouncil.evaluation.judging;

import com.debopam.llmcouncil.evaluation.domain.AnswerResult;
import com.debopam.llmcouncil.evaluation.domain.EvaluationDataset;
import com.debopam.llmcouncil.evaluation.domain.EvaluationPlan;
import com.debopam.llmcouncil.evaluation.domain.EvaluationRubric;
import com.debopam.llmcouncil.evaluation.domain.JudgePreflightResult;
import com.debopam.llmcouncil.evaluation.domain.JudgmentRecord;
import com.debopam.llmcouncil.evaluation.domain.UsageMetrics;
import com.debopam.llmcouncil.evaluation.model.ModelGatewayException;
import com.debopam.llmcouncil.evaluation.model.ModelGatewayProvider;
import com.debopam.llmcouncil.evaluation.model.ModelPrompt;
import com.debopam.llmcouncil.evaluation.model.ModelResponse;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Executes one blind orientation; orchestration decides whether a mirror is required. */
@Component
public class PairwiseJudgingService {
    private final ModelGatewayProvider models;
    private final JudgePromptFactory prompts;
    private final JudgeResponseParser parser;

    public PairwiseJudgingService(ModelGatewayProvider models, JudgePromptFactory prompts,
                                  JudgeResponseParser parser) {
        this.models = models;
        this.prompts = prompts;
        this.parser = parser;
    }

    public JudgmentRecord judge(EvaluationPlan plan, EvaluationRubric rubric,
                                EvaluationDataset.EvaluationCase evalCase,
                                EvaluationPlan.ComparisonSpec comparison,
                                EvaluationPlan.JudgeSpec judge, AnswerResult answerA,
                                AnswerResult answerB, int orientation) {
        String pairId = comparison.id() + ":" + evalCase.id() + ":r" + answerA.repetition();
        String judgmentId = pairId + ":" + judge.id() + ":o" + orientation;
        EvaluationPlan.ModelSpec model = plan.models().stream()
                .filter(value -> value.id().equals(judge.modelId())).findFirst().orElseThrow();
        Instant started = Instant.now();
        try {
            ModelResponse response = models.gateway(model).call(new ModelPrompt(judgmentId,
                    prompts.system(rubric, evalCase), prompts.user(evalCase, answerA.answer(), answerB.answer()), true));
            try {
                JudgeResponseParser.ParsedJudgment parsed = parser.parse(response.text(), rubric);
                return new JudgmentRecord(judgmentId, pairId, comparison.id(), evalCase.id(),
                        answerA.repetition(), judge.id(), orientation, answerA.variantId(), answerB.variantId(),
                        JudgmentRecord.Status.COMPLETED, parsed.winner(), parsed.confidence(),
                        parsed.scoresA(), parsed.scoresB(), parsed.violationsA(), parsed.violationsB(),
                        parsed.rationale(), response.text(), null, Instant.now(), response.durationMs(), response.usage());
            } catch (IllegalArgumentException ex) {
                return invalid(judgmentId, pairId, comparison, evalCase, answerA, answerB, judge,
                        orientation, response.text(), ex.getMessage(), response.durationMs(), response.usage());
            }
        } catch (ModelGatewayException ex) {
            return new JudgmentRecord(judgmentId, pairId, comparison.id(), evalCase.id(),
                    answerA.repetition(), judge.id(), orientation, answerA.variantId(), answerB.variantId(),
                    JudgmentRecord.Status.FAILED, null, null, null, null, null, null,
                    null, null, ex.getMessage(), Instant.now(),
                    java.time.Duration.between(started, Instant.now()).toMillis(),
                    ex.usage());
        }
    }

    /**
     * Proves that a judge can produce parseable JSON before candidate generation
     * spends minutes creating answers that cannot subsequently be adjudicated.
     */
    public JudgePreflightResult preflight(EvaluationPlan plan, EvaluationRubric rubric,
                                          EvaluationPlan.JudgeSpec judge) {
        EvaluationPlan.ModelSpec model = plan.models().stream()
                .filter(value -> value.id().equals(judge.modelId())).findFirst().orElseThrow();
        EvaluationDataset.EvaluationCase smokeCase = new EvaluationDataset.EvaluationCase(
                "judge-preflight", "preflight", "Which candidate correctly computes 2 + 2?",
                "Use ordinary integer arithmetic.", List.of("preflight"),
                List.of("Prefer the correct calculation"), List.of("2 + 2 = 4"),
                List.of("Treating 2 + 2 as 5"), List.of(), Map.of());
        Instant started = Instant.now();
        try {
            ModelResponse response = models.gateway(model).call(new ModelPrompt(
                    "judge-preflight:" + judge.id(), prompts.system(rubric, smokeCase),
                    prompts.user(smokeCase, "2 + 2 = 4.", "2 + 2 = 5."), true));
            try {
                JudgeResponseParser.ParsedJudgment parsed = parser.parse(response.text(), rubric);
                if (parsed.winner() != JudgmentRecord.Winner.A) {
                    return failedPreflight(judge, model, response.text(), "SEMANTIC_PREFLIGHT_FAILED",
                            "Judge selected " + parsed.winner()
                                    + " for a control pair whose correct answer is A",
                            response.durationMs(), response.usage());
                }
                return new JudgePreflightResult(judge.id(), model.id(),
                        JudgePreflightResult.Status.PASSED, response.text(), null, null,
                        Instant.now(), response.durationMs(), response.usage());
            } catch (IllegalArgumentException ex) {
                return failedPreflight(judge, model, response.text(), "INVALID_JUDGE_OUTPUT",
                        ex.getMessage(), response.durationMs(), response.usage());
            }
        } catch (ModelGatewayException ex) {
            return failedPreflight(judge, model, null, ex.category(), ex.getMessage(),
                    java.time.Duration.between(started, Instant.now()).toMillis(), ex.usage());
        }
    }

    private JudgmentRecord invalid(String id, String pairId, EvaluationPlan.ComparisonSpec comparison,
                                   EvaluationDataset.EvaluationCase evalCase, AnswerResult a, AnswerResult b,
                                   EvaluationPlan.JudgeSpec judge, int orientation, String raw,
                                   String reason, long duration, UsageMetrics usage) {
        return new JudgmentRecord(id, pairId, comparison.id(), evalCase.id(), a.repetition(),
                judge.id(), orientation, a.variantId(), b.variantId(), JudgmentRecord.Status.INVALID,
                null, null, null, null, null, null, null, raw, reason, Instant.now(), duration, usage);
    }

    private JudgePreflightResult failedPreflight(EvaluationPlan.JudgeSpec judge,
                                                  EvaluationPlan.ModelSpec model,
                                                  String raw, String category, String reason,
                                                  long duration, UsageMetrics usage) {
        return new JudgePreflightResult(judge.id(), model.id(),
                JudgePreflightResult.Status.FAILED, raw, category, reason,
                Instant.now(), duration, usage == null ? UsageMetrics.empty() : usage);
    }
}
