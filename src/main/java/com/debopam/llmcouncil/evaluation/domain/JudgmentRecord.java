package com.debopam.llmcouncil.evaluation.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Raw and normalized evidence from one blind pairwise judge orientation. */
public record JudgmentRecord(
        String judgmentId,
        String pairId,
        String comparisonId,
        String caseId,
        int repetition,
        String judgeId,
        int orientation,
        String answerAVariant,
        String answerBVariant,
        Status status,
        Winner winner,
        Double confidence,
        Map<String, Double> scoresA,
        Map<String, Double> scoresB,
        List<String> violationsA,
        List<String> violationsB,
        String rationale,
        String rawResponse,
        String failureReason,
        Instant completedAt,
        long durationMs,
        UsageMetrics usage
) {
    public enum Status { COMPLETED, INVALID, FAILED }
    public enum Winner { A, B, TIE }

    public String winningVariant() {
        if (status != Status.COMPLETED || winner == null || winner == Winner.TIE) return null;
        return winner == Winner.A ? answerAVariant : answerBVariant;
    }
}
