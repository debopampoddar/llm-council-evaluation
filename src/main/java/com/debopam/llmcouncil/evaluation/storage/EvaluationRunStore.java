package com.debopam.llmcouncil.evaluation.storage;

import com.debopam.llmcouncil.evaluation.config.EvaluationInputLoader;
import com.debopam.llmcouncil.evaluation.config.Hashing;
import com.debopam.llmcouncil.evaluation.config.InputHashes;
import com.debopam.llmcouncil.evaluation.domain.AnswerResult;
import com.debopam.llmcouncil.evaluation.domain.CheckResult;
import com.debopam.llmcouncil.evaluation.domain.EvaluationBundle;
import com.debopam.llmcouncil.evaluation.domain.JudgePreflightResult;
import com.debopam.llmcouncil.evaluation.domain.JudgmentRecord;
import com.debopam.llmcouncil.evaluation.domain.RunManifest;
import com.debopam.llmcouncil.evaluation.domain.RunState;
import com.debopam.llmcouncil.evaluation.domain.RuntimeEnvironment;
import com.debopam.llmcouncil.evaluation.execution.EvaluationPromptFactory;
import com.debopam.llmcouncil.evaluation.execution.UnitExecutor;
import com.debopam.llmcouncil.evaluation.judging.JudgePromptFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Atomic, one-unit-per-file evidence store supporting safe interruption and resume. */
@Component
public class EvaluationRunStore {
    private static final DateTimeFormatter RUN_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private final ObjectMapper mapper;
    private final GitFingerprint git;

    public EvaluationRunStore(ObjectMapper mapper, GitFingerprint git) {
        this.mapper = mapper.copy().findAndRegisterModules().enable(SerializationFeature.INDENT_OUTPUT);
        this.git = git;
    }

    public RunHandle create(EvaluationBundle bundle, InputHashes hashes, JsonNode catalog) {
        return create(bundle, hashes, catalog,
                RuntimeEnvironment.capture(UnitExecutor.configuredConcurrency()));
    }

    public RunHandle create(EvaluationBundle bundle, InputHashes hashes, JsonNode catalog,
                            RuntimeEnvironment runtimeEnvironment) {
        String runId = RUN_TIME.format(Instant.now()) + "-" + bundle.plan().id()
                + "-" + hashes.plan().substring(0, 8);
        Path directory = bundle.outputDirectory().resolve(runId).toAbsolutePath().normalize();
        try {
            Files.createDirectories(directory.getParent());
            Files.createDirectory(directory);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to create run directory " + directory, ex);
        }
        GitFingerprint.Fingerprint fingerprint = git.read(bundle.planPath().getParent());
        String version = Optional.ofNullable(getClass().getPackage().getImplementationVersion()).orElse("development");
        RunManifest manifest = new RunManifest(
                1, runId, Instant.now(), hashes.plan(), hashes.dataset(), hashes.rubric(),
                catalogFingerprint(catalog), catalog.path("generation").asLong(),
                fingerprint.commit(), fingerprint.dirty(), version,
                EvaluationPromptFactory.DIRECT_PROMPT_VERSION,
                EvaluationPromptFactory.ENSEMBLE_PROMPT_VERSION,
                JudgePromptFactory.VERSION,
                System.getProperty("java.version"),
                System.getProperty("os.name") + " " + System.getProperty("os.version"),
                runtimeEnvironment,
                bundle.plan(), bundle.dataset(), bundle.rubric());
        write(directory.resolve("manifest.json"), manifest);
        write(directory.resolve("preflight/catalog.json"), catalog);
        state(directory, "CREATED", "Inputs validated; no evaluation unit has run yet.");
        return new RunHandle(directory, manifest);
    }

    public RunHandle open(Path directory) {
        Path normalized = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) throw new IllegalArgumentException("Run directory does not exist: " + normalized);
        return new RunHandle(normalized, read(normalized.resolve("manifest.json"), RunManifest.class));
    }

    public RunProgress progress(Path directory) {
        RunHandle handle = open(directory);
        RunManifest manifest = handle.manifest();
        RunState current = read(handle.directory().resolve("state.json"), RunState.class);
        long enabledVariants = manifest.plan().variants().stream()
                .filter(value -> !Boolean.FALSE.equals(value.enabled())).count();
        long expectedAnswers = (long) manifest.dataset().cases().size()
                * manifest.plan().repetitions() * enabledVariants;
        long orientations = manifest.plan().judges().stream()
                .filter(value -> !Boolean.FALSE.equals(value.enabled()))
                .mapToLong(value -> Boolean.TRUE.equals(value.mirrored()) ? 2 : 1).sum();
        long comparisons = manifest.plan().comparisons().stream()
                .filter(value -> !Boolean.FALSE.equals(value.enabled())).count();
        long maximumJudgments = (long) manifest.dataset().cases().size()
                * manifest.plan().repetitions() * comparisons * orientations;
        return new RunProgress(current, answers(handle.directory()).size(), expectedAnswers,
                jsonFiles(handle.directory().resolve("checks")).size(),
                judgments(handle.directory()).size(), maximumJudgments,
                Files.isRegularFile(handle.directory().resolve("report/report.md")));
    }

    public String catalogFingerprint(JsonNode catalog) {
        try {
            ObjectNode copy = catalog.deepCopy();
            copy.remove(List.of("generation", "builtAt", "providers", "issues"));
            return Hashing.sha256(mapper.writeValueAsString(copy));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to fingerprint council catalog", ex);
        }
    }

    public void state(Path directory, String status, String detail) {
        write(directory.resolve("state.json"), new RunState(status, Instant.now(), detail));
    }

    public Optional<AnswerResult> answer(Path directory, String caseId, String variantId, int repetition) {
        return optional(answerPath(directory, caseId, variantId, repetition), AnswerResult.class);
    }

    public void answer(Path directory, AnswerResult result) {
        write(answerPath(directory, result.caseId(), result.variantId(), result.repetition()), result);
    }

    public void checks(Path directory, AnswerResult result, List<CheckResult> checks) {
        write(checkPath(directory, result.caseId(), result.variantId(), result.repetition()), checks);
    }

    public boolean checksPresent(Path directory, String caseId, String variantId, int repetition) {
        return Files.isRegularFile(checkPath(directory, caseId, variantId, repetition));
    }

    public Optional<JudgmentRecord> judgment(Path directory, String comparisonId, String caseId,
                                             int repetition, String judgeId, int orientation) {
        return optional(judgmentPath(directory, comparisonId, caseId, repetition, judgeId, orientation),
                JudgmentRecord.class);
    }

    public void judgment(Path directory, JudgmentRecord record) {
        write(judgmentPath(directory, record.comparisonId(), record.caseId(), record.repetition(),
                record.judgeId(), record.orientation()), record);
    }

    /** Preserves every raw judge attempt separately from the canonical final judgment. */
    public void judgmentAttempt(Path directory, JudgmentRecord record, int attempt) {
        write(judgmentAttemptPath(directory, record.comparisonId(), record.caseId(), record.repetition(),
                record.judgeId(), record.orientation(), attempt), record);
    }

    public List<AnswerResult> answers(Path directory) {
        return readTree(directory.resolve("answers"), AnswerResult.class);
    }

    public List<CheckResult> checks(Path directory) {
        List<CheckResult> results = new ArrayList<>();
        for (Path path : jsonFiles(directory.resolve("checks"))) {
            try {
                CheckResult[] values = mapper.readValue(path.toFile(), CheckResult[].class);
                results.addAll(List.of(values));
            } catch (IOException ex) {
                throw new IllegalStateException("Unable to read " + path, ex);
            }
        }
        return List.copyOf(results);
    }

    public List<JudgmentRecord> judgments(Path directory) {
        return readTree(directory.resolve("judgments"), JudgmentRecord.class);
    }

    public Optional<JudgePreflightResult> judgePreflight(Path directory, String judgeId) {
        return optional(directory.resolve("preflight/judges").resolve(judgeId + ".json"),
                JudgePreflightResult.class);
    }

    public void judgePreflight(Path directory, JudgePreflightResult result) {
        write(directory.resolve("preflight/judges").resolve(result.judgeId() + ".json"), result);
    }

    public List<JudgePreflightResult> judgePreflights(Path directory) {
        return readTree(directory.resolve("preflight/judges"), JudgePreflightResult.class);
    }

    public void writeReport(Path directory, String relativePath, String content) {
        writeText(directory.resolve(relativePath), content);
    }

    public void writeArtifact(Path directory, String relativePath, Object value) {
        write(directory.resolve(relativePath), value);
    }

    public <T> Optional<T> readArtifact(Path directory, String relativePath, Class<T> type) {
        return optional(directory.resolve(relativePath), type);
    }

    private Path answerPath(Path root, String caseId, String variantId, int repetition) {
        return root.resolve("answers").resolve(caseId).resolve(variantId).resolve(rep(repetition));
    }
    private Path checkPath(Path root, String caseId, String variantId, int repetition) {
        return root.resolve("checks").resolve(caseId).resolve(variantId).resolve(rep(repetition));
    }
    private Path judgmentPath(Path root, String comparisonId, String caseId, int repetition,
                              String judgeId, int orientation) {
        return root.resolve("judgments").resolve(comparisonId).resolve(caseId)
                .resolve("r" + String.format("%02d", repetition)).resolve(judgeId)
                .resolve("o" + orientation + ".json");
    }
    private Path judgmentAttemptPath(Path root, String comparisonId, String caseId, int repetition,
                                     String judgeId, int orientation, int attempt) {
        return root.resolve("judgment-attempts").resolve(comparisonId).resolve(caseId)
                .resolve("r" + String.format("%02d", repetition)).resolve(judgeId)
                .resolve("o" + orientation).resolve("attempt-" + attempt + ".json");
    }
    private String rep(int repetition) { return "r" + String.format("%02d", repetition) + ".json"; }

    private <T> List<T> readTree(Path root, Class<T> type) {
        List<T> values = new ArrayList<>();
        for (Path path : jsonFiles(root)) values.add(read(path, type));
        return List.copyOf(values);
    }

    private List<Path> jsonFiles(Path root) {
        if (!Files.isDirectory(root)) return List.of();
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                    .sorted().toList();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to list " + root, ex);
        }
    }

    private <T> Optional<T> optional(Path path, Class<T> type) {
        return Files.isRegularFile(path) ? Optional.of(read(path, type)) : Optional.empty();
    }

    private <T> T read(Path path, Class<T> type) {
        try {
            return mapper.readValue(path.toFile(), type);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read " + path, ex);
        }
    }

    private void write(Path path, Object value) {
        try {
            Files.createDirectories(path.getParent());
            Path temp = Files.createTempFile(path.getParent(), "." + path.getFileName(), ".tmp");
            try {
                mapper.writeValue(temp.toFile(), value);
                atomicMove(temp, path);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to atomically write " + path, ex);
        }
    }

    private void writeText(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Path temp = Files.createTempFile(path.getParent(), "." + path.getFileName(), ".tmp");
            try {
                Files.writeString(temp, content, StandardCharsets.UTF_8);
                atomicMove(temp, path);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to atomically write " + path, ex);
        }
    }

    private void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record RunHandle(Path directory, RunManifest manifest) {}
    public record RunProgress(RunState state, long answers, long expectedAnswers,
                              long checkFiles, long judgments, long maximumJudgments,
                              boolean reportAvailable) {}
}
