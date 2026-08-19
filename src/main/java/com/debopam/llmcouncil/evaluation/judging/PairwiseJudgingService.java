package com.debopam.llmcouncil.evaluation.judging;

import com.debopam.llmcouncil.evaluation.domain.AnswerResult;
import com.debopam.llmcouncil.evaluation.domain.EvaluationDataset;
import com.debopam.llmcouncil.evaluation.domain.EvaluationPlan;
import com.debopam.llmcouncil.evaluation.domain.EvaluationRubric;
import com.debopam.llmcouncil.evaluation.domain.JudgmentRecord;
import com.debopam.llmcouncil.evaluation.domain.UsageMetrics;
import com.debopam.llmcouncil.evaluation.model.ModelGatewayException;
import com.debopam.llmcouncil.evaluation.model.ModelGatewayProvider;
import com.debopam.llmcouncil.evaluation.model.ModelPrompt;
import com.debopam.llmcouncil.evaluation.model.ModelResponse;
import org.springframework.stereotype.Component;

import java.time.Instant;

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
                    new UsageMetrics(ex.attemptedCalls(), 0, 0, null, true, false));
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
}
