package com.debopam.llmcouncil.evaluation.judging;

import com.debopam.llmcouncil.evaluation.TestFixtures;
import com.debopam.llmcouncil.evaluation.config.InputHashes;
import com.debopam.llmcouncil.evaluation.domain.AnswerResult;
import com.debopam.llmcouncil.evaluation.domain.UsageMetrics;
import com.debopam.llmcouncil.evaluation.storage.EvaluationRunStore;
import com.debopam.llmcouncil.evaluation.storage.GitFingerprint;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HumanReviewExporterTest {
    @TempDir Path temp;

    @Test
    void importsOnlyKnownBlindedPairsAndNormalizesWinnerToVariant() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        EvaluationRunStore store = new EvaluationRunStore(mapper, new GitFingerprint());
        var bundle = TestFixtures.bundle(temp);
        var handle = store.create(bundle,
                new InputHashes("a".repeat(64), "b".repeat(64), "c".repeat(64)),
                TestFixtures.catalog());
        HumanReviewExporter exporter = new HumanReviewExporter(store, mapper);
        exporter.export(handle.directory(), bundle, List.of(
                answer("direct", "Direct answer"), answer("council", "Council answer")));

        HumanReviewExporter.HumanPair[] packet = store.readArtifact(handle.directory(),
                "human/human-review-template.json", HumanReviewExporter.HumanPair[].class).orElseThrow();
        Path decisions = temp.resolve("decisions.json");
        Files.writeString(decisions, mapper.writeValueAsString(List.of(
                new HumanReviewExporter.HumanDecision(packet[0].pairId(), "A", "More accurate and complete."))));

        assertEquals(1, exporter.importDecisions(handle.directory(), decisions));
        HumanReviewExporter.NormalizedHumanDecision[] normalized = store.readArtifact(handle.directory(),
                "human/human-review-normalized.json",
                HumanReviewExporter.NormalizedHumanDecision[].class).orElseThrow();
        assertEquals(1, normalized.length);
        assertEquals(HumanReviewExporter.HumanWinner.A, normalized[0].winner());
        HumanReviewExporter.HumanKey[] keys = store.readArtifact(handle.directory(),
                "human/human-review-key.json", HumanReviewExporter.HumanKey[].class).orElseThrow();
        assertEquals(keys[0].answerAVariant(), normalized[0].winnerVariant());
    }

    @Test
    void rejectsUnknownPairIds() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        EvaluationRunStore store = new EvaluationRunStore(mapper, new GitFingerprint());
        var bundle = TestFixtures.bundle(temp);
        var handle = store.create(bundle,
                new InputHashes("a".repeat(64), "b".repeat(64), "c".repeat(64)),
                TestFixtures.catalog());
        HumanReviewExporter exporter = new HumanReviewExporter(store, mapper);
        exporter.export(handle.directory(), bundle, List.of(
                answer("direct", "Direct answer"), answer("council", "Council answer")));
        Path decisions = temp.resolve("bad-decisions.json");
        Files.writeString(decisions, "[{\"pairId\":\"unknown\",\"winner\":\"TIE\",\"rationale\":\"Equal.\"}]");

        assertThrows(IllegalArgumentException.class,
                () -> exporter.importDecisions(handle.directory(), decisions));
    }

    private AnswerResult answer(String variant, String text) {
        return new AnswerResult("case-1:" + variant + ":r1", "case-1", variant, 1,
                AnswerResult.AnswerStatus.COMPLETED, text, Instant.now(), Instant.now(), 1,
                new UsageMetrics(1, 1, 1, 0.0, false, false), null, null,
                List.of(), List.of(), null);
    }
}
