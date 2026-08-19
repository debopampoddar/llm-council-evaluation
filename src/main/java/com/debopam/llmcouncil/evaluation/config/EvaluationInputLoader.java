package com.debopam.llmcouncil.evaluation.config;

import com.debopam.llmcouncil.evaluation.domain.EvaluationBundle;
import com.debopam.llmcouncil.evaluation.domain.EvaluationDataset;
import com.debopam.llmcouncil.evaluation.domain.EvaluationPlan;
import com.debopam.llmcouncil.evaluation.domain.EvaluationRubric;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Strict loader: misspelled YAML fields are errors, never silent no-ops. */
@Component
public class EvaluationInputLoader {
    private static final long MAX_INPUT_BYTES = 2_000_000;

    private final ObjectMapper yaml;
    private final ObjectMapper json;
    private final EvaluationInputValidator validator;

    public EvaluationInputLoader(EvaluationInputValidator validator) {
        this.validator = validator;
        this.yaml = configured(new ObjectMapper(new YAMLFactory()));
        this.json = configured(new ObjectMapper());
    }

    public LoadedInputs load(Path planPath) {
        Path normalizedPlan = requireFile(planPath);
        EvaluationPlan plan = read(normalizedPlan, EvaluationPlan.class, yaml);
        Path parent = normalizedPlan.getParent() == null ? Path.of(".").toAbsolutePath() : normalizedPlan.getParent();
        Path datasetPath = resolve(parent, plan.dataset(), "dataset");
        Path rubricPath = resolve(parent, plan.rubric(), "rubric");
        EvaluationDataset dataset = read(datasetPath, EvaluationDataset.class, yaml);
        EvaluationRubric rubric = read(rubricPath, EvaluationRubric.class, yaml);
        Path output = resolveOutput(parent, plan.outputDirectory());
        EvaluationBundle bundle = new EvaluationBundle(plan, dataset, rubric, normalizedPlan,
                datasetPath, rubricPath, output);
        validator.validate(bundle);
        return new LoadedInputs(bundle, new InputHashes(
                hash(plan), hash(dataset), hash(rubric)));
    }

    public EvaluationBundle fromManifest(com.debopam.llmcouncil.evaluation.domain.RunManifest manifest,
                                         Path runDirectory) {
        EvaluationBundle bundle = new EvaluationBundle(
                manifest.plan(), manifest.dataset(), manifest.rubric(),
                runDirectory.resolve("manifest.json"), null, null, runDirectory.getParent());
        validator.validate(bundle);
        return bundle;
    }

    public ObjectMapper jsonMapper() {
        return json.copy();
    }

    private String hash(Object value) {
        try {
            return Hashing.sha256(json.writeValueAsString(value));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to hash evaluation input", ex);
        }
    }

    private <T> T read(Path path, Class<T> type, ObjectMapper mapper) {
        try {
            return mapper.readValue(Files.readString(requireFile(path)), type);
        } catch (EvaluationConfigurationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new EvaluationConfigurationException(java.util.List.of(
                    "Unable to parse " + path + ": " + rootMessage(ex)));
        }
    }

    private Path resolve(Path parent, String configured, String name) {
        if (configured == null || configured.isBlank()) {
            throw new EvaluationConfigurationException(java.util.List.of("Plan " + name + " path is required"));
        }
        Path path = Path.of(configured);
        return requireFile(path.isAbsolute() ? path : parent.resolve(path));
    }

    private Path resolveOutput(Path parent, String configured) {
        String value = configured == null || configured.isBlank() ? "results" : configured;
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : parent.resolve(path)).toAbsolutePath().normalize();
    }

    private Path requireFile(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        try {
            if (!Files.isRegularFile(normalized)) {
                throw new EvaluationConfigurationException(java.util.List.of("Input file does not exist: " + normalized));
            }
            if (Files.size(normalized) > MAX_INPUT_BYTES) {
                throw new EvaluationConfigurationException(java.util.List.of(
                        "Input file exceeds " + MAX_INPUT_BYTES + " bytes: " + normalized));
            }
            return normalized;
        } catch (IOException ex) {
            throw new EvaluationConfigurationException(java.util.List.of("Cannot read input file " + normalized));
        }
    }

    private ObjectMapper configured(ObjectMapper mapper) {
        return mapper.findAndRegisterModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return message == null ? current.getClass().getSimpleName() : message.split("\\R")[0];
    }

    public record LoadedInputs(EvaluationBundle bundle, InputHashes hashes) {}
}
