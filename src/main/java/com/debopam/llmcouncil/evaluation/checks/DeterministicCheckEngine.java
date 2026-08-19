package com.debopam.llmcouncil.evaluation.checks;

import com.debopam.llmcouncil.evaluation.domain.AnswerResult;
import com.debopam.llmcouncil.evaluation.domain.CheckResult;
import com.debopam.llmcouncil.evaluation.domain.EvaluationDataset;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Executes only objective, declared checks; semantic expectations stay with judges. */
@Component
public class DeterministicCheckEngine {
    public List<CheckResult> evaluate(EvaluationDataset.EvaluationCase evalCase, AnswerResult answer) {
        List<CheckResult> results = new ArrayList<>();
        List<EvaluationDataset.CheckSpec> checks = evalCase.deterministicChecks() == null
                ? List.of() : evalCase.deterministicChecks();
        for (int index = 0; index < checks.size(); index++) {
            EvaluationDataset.CheckSpec check = checks.get(index);
            try {
                results.add(run(answer, check, index));
            } catch (RuntimeException ex) {
                results.add(new CheckResult(answer.unitId(), index, check.type(), CheckResult.Status.ERROR,
                        ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
            }
        }
        return List.copyOf(results);
    }

    private CheckResult run(AnswerResult result, EvaluationDataset.CheckSpec check, int index) {
        String answer = result.answer() == null ? "" : result.answer();
        boolean sensitive = Boolean.TRUE.equals(check.caseSensitive());
        String compared = sensitive ? answer : answer.toLowerCase(Locale.ROOT);
        List<String> values = new ArrayList<>(check.values() == null ? List.of() : check.values());
        if (check.value() != null && !check.value().isBlank()) values.add(check.value());
        String type = check.type().toLowerCase(Locale.ROOT);
        boolean passed;
        String detail;
        switch (type) {
            case "non-blank" -> {
                passed = !answer.isBlank();
                detail = passed ? "Answer is not blank" : "Answer is blank";
            }
            case "contains-all" -> {
                List<String> missing = values.stream().filter(value -> !compared.contains(normalize(value, sensitive))).toList();
                passed = missing.isEmpty();
                detail = passed ? "All required values are present" : "Missing: " + missing;
            }
            case "contains-any" -> {
                passed = values.stream().anyMatch(value -> compared.contains(normalize(value, sensitive)));
                detail = passed ? "At least one required value is present" : "None of these values were present: " + values;
            }
            case "contains-none" -> {
                List<String> found = values.stream().filter(value -> compared.contains(normalize(value, sensitive))).toList();
                passed = found.isEmpty();
                detail = passed ? "No forbidden value is present" : "Forbidden values present: " + found;
            }
            case "regex", "forbidden-regex" -> {
                int flags = sensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
                boolean found = Pattern.compile(check.pattern(), flags).matcher(answer).find();
                passed = "regex".equals(type) ? found : !found;
                detail = passed ? "Pattern condition satisfied" : "Pattern condition failed: " + check.pattern();
            }
            case "max-chars" -> {
                passed = answer.length() <= check.max();
                detail = answer.length() + " characters; maximum " + check.max();
            }
            default -> throw new IllegalArgumentException("Unknown check type: " + check.type());
        }
        return new CheckResult(result.unitId(), index, check.type(),
                passed ? CheckResult.Status.PASS : CheckResult.Status.FAIL, detail);
    }

    private String normalize(String value, boolean sensitive) {
        return sensitive ? value : value.toLowerCase(Locale.ROOT);
    }
}
