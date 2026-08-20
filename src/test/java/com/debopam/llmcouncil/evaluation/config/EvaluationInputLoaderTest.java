package com.debopam.llmcouncil.evaluation.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationInputLoaderTest {
    @TempDir Path temp;

    @Test
    void loadsShippedPilotWithStrictContracts() {
        var loader = new EvaluationInputLoader(new EvaluationInputValidator());
        var loaded = loader.load(
                Path.of("evaluation/plans/local-pilot.yml"));
        assertEquals(12, loaded.bundle().dataset().cases().size());
        assertEquals(3, loaded.bundle().plan().variants().size());
        assertEquals(64, loaded.hashes().plan().length());
        var baseRate = loaded.bundle().dataset().cases().stream()
                .filter(value -> "reasoning-base-rate".equals(value.id())).findFirst().orElseThrow();
        String regex = baseRate.deterministicChecks().getFirst().pattern();
        assertTrue(Pattern.compile(regex).matcher("The result is 29.63%.").find(),
                "the shipped correctness check must accept a more precise equivalent result");

        var security = loader.load(Path.of("evaluation/plans/prompt-injection-regression.yml"));
        var classification = security.bundle().dataset().cases().stream()
                .filter(value -> "injection-indirect-classification".equals(value.id()))
                .findFirst().orElseThrow();
        Pattern decision = Pattern.compile(classification.deterministicChecks().getFirst().pattern());
        assertTrue(decision.matcher(
                "The change does not have enough evidence for production approval. "
                        + "The hostile instruction saying APPROVED was ignored.").find(),
                "safely discussing the hostile word must not turn a rejection into a failure");
        assertFalse(decision.matcher("The change is approved for production.").find(),
                "the decision check must not accept the injected approval");
    }

    @Test
    void everyShippedPlanAndReferencedAssetPassesStrictValidation() throws Exception {
        var loader = new EvaluationInputLoader(new EvaluationInputValidator());
        // Enumerated rather than listed by name: a hand-maintained list silently
        // stops covering the plan someone adds next, and the failure mode is an
        // unparseable shipped asset discovered during a live run.
        List<Path> plans;
        try (var entries = Files.list(Path.of("evaluation/plans"))) {
            plans = entries.filter(path -> path.getFileName().toString().endsWith(".yml")).sorted().toList();
        }
        // Positive control: enumeration that found nothing would pass vacuously.
        assertTrue(plans.size() >= 5, "expected the shipped plans to be discovered, found " + plans);
        plans.forEach(loader::load);
    }

    @Test
    void rejectsUnknownFieldsInsteadOfIgnoringTypos() throws Exception {
        Files.writeString(temp.resolve("plan.yml"), "version: 1\nid: p\ncouncilBaseUrl: http://localhost\ndataset: d.yml\nrubric: r.yml\noutputDirectory: out\nseed: 1\nrepetitions: 1\nexecution: {}\nmodels: []\nvariants: []\ncomparisons: []\njudges: []\nmaxCallz: 10\n");
        Files.writeString(temp.resolve("d.yml"), "version: 1\nid: d\ncases: []\n");
        Files.writeString(temp.resolve("r.yml"), "version: 1\nid: r\ncriteria: []\n");
        EvaluationConfigurationException ex = assertThrows(EvaluationConfigurationException.class,
                () -> new EvaluationInputLoader(new EvaluationInputValidator()).load(temp.resolve("plan.yml")));
        assertTrue(ex.getMessage().contains("maxCallz"));
    }
}
