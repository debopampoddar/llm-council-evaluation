package com.debopam.llmcouncil.evaluation.judging;

import com.debopam.llmcouncil.evaluation.domain.EvaluationRubric;
import com.debopam.llmcouncil.evaluation.domain.JudgmentRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Recovers one JSON object, then validates exact criterion coverage and numeric ranges. */
@Component
public class JudgeResponseParser {
    private final ObjectMapper mapper;

    public JudgeResponseParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ParsedJudgment parse(String raw, EvaluationRubric rubric) {
        try {
            JsonNode root = mapper.readTree(jsonObject(raw));
            exactFields(root, Set.of("winner", "confidence", "scores", "violations", "rationale"), "root");
            exactFields(root.path("scores"), Set.of("A", "B"), "scores");
            exactFields(root.path("violations"), Set.of("A", "B"), "violations");
            JudgmentRecord.Winner winner = JudgmentRecord.Winner.valueOf(root.path("winner").asText());
            double confidence = number(root.get("confidence"), "confidence");
            if (confidence < 0 || confidence > 1) throw new IllegalArgumentException("confidence must be between 0 and 1");
            Set<String> expected = new LinkedHashSet<>();
            rubric.criteria().forEach(value -> expected.add(value.id()));
            Map<String, Double> scoresA = scores(root.path("scores").path("A"), expected, "A");
            Map<String, Double> scoresB = scores(root.path("scores").path("B"), expected, "B");
            List<String> violationsA = strings(root.path("violations").path("A"), "violations.A");
            List<String> violationsB = strings(root.path("violations").path("B"), "violations.B");
            String rationale = root.path("rationale").asText("").trim();
            if (rationale.isBlank()) throw new IllegalArgumentException("rationale is required");
            return new ParsedJudgment(winner, confidence, scoresA, scoresB,
                    violationsA, violationsB, rationale);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid judge JSON: " + safe(ex), ex);
        }
    }

    private Map<String, Double> scores(JsonNode node, Set<String> expected, String label) {
        if (!node.isObject()) throw new IllegalArgumentException("scores." + label + " must be an object");
        Set<String> actual = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw new IllegalArgumentException(
                "scores." + label + " criteria mismatch; expected " + expected + ", got " + actual);
        Map<String, Double> scores = new LinkedHashMap<>();
        for (String criterion : expected) {
            double value = number(node.get(criterion), "scores." + label + "." + criterion);
            if (value < 0 || value > 100) throw new IllegalArgumentException("Score must be between 0 and 100: " + criterion);
            scores.put(criterion, value);
        }
        return Map.copyOf(scores);
    }

    private List<String> strings(JsonNode node, String field) {
        if (!node.isArray()) throw new IllegalArgumentException(field + " must be an array");
        List<String> values = new ArrayList<>();
        node.forEach(value -> {
            if (!value.isTextual() || value.asText().isBlank())
                throw new IllegalArgumentException(field + " entries must be non-blank strings");
            values.add(value.asText());
        });
        return List.copyOf(values);
    }

    private double number(JsonNode node, String field) {
        if (node == null || !node.isNumber()) throw new IllegalArgumentException(field + " must be numeric");
        double value = node.doubleValue();
        if (!Double.isFinite(value)) throw new IllegalArgumentException(field + " must be finite");
        return value;
    }

    private void exactFields(JsonNode node, Set<String> expected, String field) {
        if (!node.isObject()) throw new IllegalArgumentException(field + " must be an object");
        Set<String> actual = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw new IllegalArgumentException(
                field + " fields mismatch; expected " + expected + ", got " + actual);
    }

    private String jsonObject(String raw) {
        if (raw == null) throw new IllegalArgumentException("Judge response was null");
        int first = raw.indexOf('{');
        int last = raw.lastIndexOf('}');
        if (first < 0 || last < first) throw new IllegalArgumentException("Judge response contained no JSON object");
        return raw.substring(first, last + 1);
    }
    private String safe(Throwable value) { return value.getMessage() == null ? value.getClass().getSimpleName() : value.getMessage(); }

    public record ParsedJudgment(
            JudgmentRecord.Winner winner,
            double confidence,
            Map<String, Double> scoresA,
            Map<String, Double> scoresB,
            List<String> violationsA,
            List<String> violationsB,
            String rationale
    ) {}
}
