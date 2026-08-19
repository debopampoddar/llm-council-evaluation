package com.debopam.llmcouncil.evaluation.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationInputLoaderTest {
    @TempDir Path temp;

    @Test
    void loadsShippedPilotWithStrictContracts() {
        var loaded = new EvaluationInputLoader(new EvaluationInputValidator()).load(
                Path.of("evaluation/plans/local-pilot.yml"));
        assertEquals(12, loaded.bundle().dataset().cases().size());
        assertEquals(3, loaded.bundle().plan().variants().size());
        assertEquals(64, loaded.hashes().plan().length());
        var baseRate = loaded.bundle().dataset().cases().stream()
                .filter(value -> "reasoning-base-rate".equals(value.id())).findFirst().orElseThrow();
        String regex = baseRate.deterministicChecks().getFirst().pattern();
        assertTrue(Pattern.compile(regex).matcher("The result is 29.63%.").find(),
                "the shipped correctness check must accept a more precise equivalent result");
    }

    @Test
    void everyShippedPlanAndReferencedAssetPassesStrictValidation() {
        var loader = new EvaluationInputLoader(new EvaluationInputValidator());
        List.of("local-pilot.yml", "local-ablation.yml", "rigorous-stage-coverage.yml",
                        "publishable-template.yml")
                .forEach(name -> loader.load(Path.of("evaluation/plans").resolve(name)));
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
