package com.debopam.llmcouncil.evaluation.statistics;

import com.debopam.llmcouncil.evaluation.domain.EvaluationPlan;
import com.debopam.llmcouncil.evaluation.domain.JudgmentRecord;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Collapses orientations first and judges second, avoiding pseudo-replication. */
@Component
public class JudgmentAggregator {
    public List<PairOutcome> aggregate(EvaluationPlan plan, List<JudgmentRecord> records) {
        Map<String, EvaluationPlan.ComparisonSpec> comparisons = plan.comparisons().stream()
                .collect(Collectors.toMap(EvaluationPlan.ComparisonSpec::id, value -> value));
        Map<String, EvaluationPlan.JudgeSpec> judges = plan.judges().stream()
                .collect(Collectors.toMap(EvaluationPlan.JudgeSpec::id, value -> value));
        Map<String, List<JudgmentRecord>> byPair = records.stream()
                .collect(Collectors.groupingBy(JudgmentRecord::pairId, LinkedHashMap::new, Collectors.toList()));
        List<PairOutcome> outcomes = new ArrayList<>();
        byPair.forEach((pairId, pairRecords) -> {
            JudgmentRecord first = pairRecords.getFirst();
            EvaluationPlan.ComparisonSpec comparison = comparisons.get(first.comparisonId());
            Map<String, List<JudgmentRecord>> byJudge = pairRecords.stream()
                    .collect(Collectors.groupingBy(JudgmentRecord::judgeId));
            int left = 0;
            int right = 0;
            int ties = 0;
            int unstable = 0;
            int invalid = 0;
            List<EvaluationPlan.JudgeSpec> expectedJudges = plan.judges().stream()
                    .filter(judge -> !Boolean.FALSE.equals(judge.enabled())).toList();
            for (EvaluationPlan.JudgeSpec judge : expectedJudges) {
                JudgeVote vote = vote(byJudge.getOrDefault(judge.id(), List.of()), Boolean.TRUE.equals(judge.mirrored()));
                switch (vote.status()) {
                    case STABLE -> {
                        if (vote.winnerVariant() == null) ties++;
                        else if (vote.winnerVariant().equals(comparison.left())) left++;
                        else if (vote.winnerVariant().equals(comparison.right())) right++;
                        else invalid++;
                    }
                    case POSITION_UNSTABLE -> unstable++;
                    case INVALID -> invalid++;
                }
            }
            OutcomeStatus status;
            String winner = null;
            int expected = expectedJudges.size();
            if (left > expected / 2.0) { status = OutcomeStatus.DECIDED; winner = comparison.left(); }
            else if (right > expected / 2.0) { status = OutcomeStatus.DECIDED; winner = comparison.right(); }
            else if (ties == expected && expected > 0) status = OutcomeStatus.TIE;
            else if (unstable > 0 && left == 0 && right == 0) status = OutcomeStatus.POSITION_UNSTABLE;
            else if (left > 0 || right > 0 || ties > 0) status = OutcomeStatus.JUDGE_DISAGREEMENT;
            else status = OutcomeStatus.INVALID;
            outcomes.add(new PairOutcome(pairId, first.comparisonId(), first.caseId(), first.repetition(),
                    status, winner, left, right, ties, unstable, invalid));
        });
        return List.copyOf(outcomes);
    }

    private JudgeVote vote(List<JudgmentRecord> records, boolean mirrored) {
        List<JudgmentRecord> completed = records.stream()
                .filter(record -> record.status() == JudgmentRecord.Status.COMPLETED)
                .sorted(java.util.Comparator.comparingInt(JudgmentRecord::orientation)).toList();
        if (completed.isEmpty() || (mirrored && completed.size() < 2)) return new JudgeVote(VoteStatus.INVALID, null);
        String first = mapped(completed.getFirst());
        if (!mirrored) return new JudgeVote(VoteStatus.STABLE, first);
        String second = mapped(completed.get(1));
        if (java.util.Objects.equals(first, second)) return new JudgeVote(VoteStatus.STABLE, first);
        return new JudgeVote(VoteStatus.POSITION_UNSTABLE, null);
    }

    private String mapped(JudgmentRecord record) {
        return record.winner() == JudgmentRecord.Winner.TIE ? null : record.winningVariant();
    }

    private enum VoteStatus { STABLE, POSITION_UNSTABLE, INVALID }
    private record JudgeVote(VoteStatus status, String winnerVariant) {}

    public enum OutcomeStatus { DECIDED, TIE, POSITION_UNSTABLE, JUDGE_DISAGREEMENT, INVALID }
    public record PairOutcome(String pairId, String comparisonId, String caseId, int repetition,
                              OutcomeStatus status, String winnerVariant, int leftVotes,
                              int rightVotes, int tieVotes, int unstableJudges, int invalidJudges) {}
}
