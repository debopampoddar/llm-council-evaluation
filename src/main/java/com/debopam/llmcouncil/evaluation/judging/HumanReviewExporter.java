package com.debopam.llmcouncil.evaluation.judging;

import com.debopam.llmcouncil.evaluation.domain.AnswerResult;
import com.debopam.llmcouncil.evaluation.domain.EvaluationBundle;
import com.debopam.llmcouncil.evaluation.domain.EvaluationDataset;
import com.debopam.llmcouncil.evaluation.domain.EvaluationPlan;
import com.debopam.llmcouncil.evaluation.storage.EvaluationRunStore;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

/** Writes a blinded review packet and a separate reveal key; neither changes model judgments. */
@Component
public class HumanReviewExporter {
    private final EvaluationRunStore store;
    private final ObjectMapper mapper;

    public HumanReviewExporter(EvaluationRunStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper.copy().findAndRegisterModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public void export(Path runDirectory, EvaluationBundle bundle, List<AnswerResult> answers) {
        Map<String, AnswerResult> byUnit = answers.stream().collect(Collectors.toMap(
                value -> key(value.caseId(), value.variantId(), value.repetition()), Function.identity()));
        List<HumanPair> packet = new ArrayList<>();
        List<HumanKey> key = new ArrayList<>();
        for (EvaluationPlan.ComparisonSpec comparison : bundle.plan().comparisons()) {
            if (Boolean.FALSE.equals(comparison.enabled())) continue;
            for (EvaluationDataset.EvaluationCase evalCase : bundle.dataset().cases()) {
                for (int repetition = 1; repetition <= bundle.plan().repetitions(); repetition++) {
                    AnswerResult left = byUnit.get(key(evalCase.id(), comparison.left(), repetition));
                    AnswerResult right = byUnit.get(key(evalCase.id(), comparison.right(), repetition));
                    if (!judgeable(left) || !judgeable(right)) continue;
                    String pairId = comparison.id() + ":" + evalCase.id() + ":r" + repetition;
                    boolean leftFirst = BlindOrder.leftFirst(bundle.plan().seed(), pairId);
                    AnswerResult a = leftFirst ? left : right;
                    AnswerResult b = leftFirst ? right : left;
                    packet.add(new HumanPair(pairId, evalCase.category(), evalCase.question(), evalCase.context(),
                            evalCase.requirements(), evalCase.referenceFacts(), evalCase.redFlags(),
                            a.answer(), b.answer(), null, null));
                    key.add(new HumanKey(pairId, a.variantId(), b.variantId()));
                }
            }
        }
        store.writeArtifact(runDirectory, "human/human-review-template.json", packet);
        store.writeArtifact(runDirectory, "human/human-review-key.json", key);
    }

    /** Imports only decisions; answers and reveal mappings remain immutable run evidence. */
    public int importDecisions(Path runDirectory, Path decisionsFile) {
        try {
            Path source = decisionsFile.toAbsolutePath().normalize();
            if (!Files.isRegularFile(source) || Files.size(source) > 2_000_000)
                throw new IllegalArgumentException("Human decision file is missing or too large: " + source);
            HumanDecision[] decisions = mapper.readValue(source.toFile(), HumanDecision[].class);
            HumanKey[] keys = store.readArtifact(runDirectory, "human/human-review-key.json", HumanKey[].class)
                    .orElseThrow(() -> new IllegalStateException("Run has no human review key"));
            Map<String, HumanKey> byPair = java.util.Arrays.stream(keys)
                    .collect(Collectors.toMap(HumanKey::pairId, Function.identity()));
            Set<String> seen = new HashSet<>();
            List<NormalizedHumanDecision> normalized = new ArrayList<>();
            for (HumanDecision decision : decisions) {
                if (decision == null || decision.pairId() == null || !seen.add(decision.pairId()))
                    throw new IllegalArgumentException("Human decisions contain a null or duplicate pairId");
                HumanKey key = byPair.get(decision.pairId());
                if (key == null) throw new IllegalArgumentException("Unknown human-review pairId: " + decision.pairId());
                HumanWinner winner;
                try { winner = HumanWinner.valueOf(decision.winner()); }
                catch (Exception ex) { throw new IllegalArgumentException("Winner must be A, B, or TIE for " + decision.pairId()); }
                if (decision.rationale() == null || decision.rationale().isBlank())
                    throw new IllegalArgumentException("Rationale is required for " + decision.pairId());
                String variant = winner == HumanWinner.TIE ? null
                        : winner == HumanWinner.A ? key.answerAVariant() : key.answerBVariant();
                normalized.add(new NormalizedHumanDecision(decision.pairId(), winner, variant, decision.rationale()));
            }
            store.writeArtifact(runDirectory, "human/human-review-completed.json", decisions);
            store.writeArtifact(runDirectory, "human/human-review-normalized.json", normalized);
            return normalized.size();
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("Unable to read human decision file: " + ex.getMessage(), ex);
        }
    }

    private boolean judgeable(AnswerResult value) {
        return value != null && (value.status() == AnswerResult.AnswerStatus.COMPLETED
                || value.status() == AnswerResult.AnswerStatus.PARTIAL) && !value.answer().isBlank();
    }
    private String key(String caseId, String variantId, int repetition) {
        return caseId + ":" + variantId + ":" + repetition;
    }

    public record HumanPair(String pairId, String category, String question, String context,
                            List<String> requirements, List<String> referenceFacts, List<String> redFlags,
                            String answerA, String answerB, String winner, String rationale) {}
    public record HumanKey(String pairId, String answerAVariant, String answerBVariant) {}
    public record HumanDecision(String pairId, String winner, String rationale) {}
    public enum HumanWinner { A, B, TIE }
    public record NormalizedHumanDecision(String pairId, HumanWinner winner,
                                          String winnerVariant, String rationale) {}
}
