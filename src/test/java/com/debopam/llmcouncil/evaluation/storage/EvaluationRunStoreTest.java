package com.debopam.llmcouncil.evaluation.storage;

import com.debopam.llmcouncil.evaluation.TestFixtures;
import com.debopam.llmcouncil.evaluation.config.InputHashes;
import com.debopam.llmcouncil.evaluation.domain.AnswerResult;
import com.debopam.llmcouncil.evaluation.domain.UsageMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationRunStoreTest {
    @TempDir Path temp;

    @Test
    void storesOneAtomicFilePerUnitAndReopensManifest() {
        var store = new EvaluationRunStore(new ObjectMapper().findAndRegisterModules(), new GitFingerprint());
        var handle = store.create(TestFixtures.bundle(temp), new InputHashes("a".repeat(64), "b".repeat(64), "c".repeat(64)),
                TestFixtures.catalog());
        var answer = new AnswerResult("case-1:direct:r1", "case-1", "direct", 1,
                AnswerResult.AnswerStatus.COMPLETED, "four", Instant.now(), Instant.now(), 1,
                new UsageMetrics(1, 2, 1, 0.0, false, false), null, null, List.of(), List.of(), null);
        store.answer(handle.directory(), answer);

        AnswerResult restored = store.answer(handle.directory(), "case-1", "direct", 1).orElseThrow();
        assertEquals(answer.unitId(), restored.unitId());
        assertEquals(answer.answer(), restored.answer());
        assertEquals(answer.usage(), restored.usage());
        assertEquals(answer.status(), restored.status());
        assertEquals(handle.manifest(), store.open(handle.directory()).manifest());
        assertTrue(Files.isRegularFile(handle.directory().resolve("answers/case-1/direct/r01.json")));
        assertTrue(store.catalogFingerprint(TestFixtures.catalog()).matches("[0-9a-f]{64}"));
        EvaluationRunStore.RunProgress progress = store.progress(handle.directory());
        assertEquals(1, progress.answers());
        assertEquals(2, progress.expectedAnswers());
        assertEquals(2, progress.maximumJudgments());
        assertEquals("CREATED", progress.state().status());
    }
}
