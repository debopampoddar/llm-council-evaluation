package com.debopam.llmcouncil.evaluation.cli;

import com.debopam.llmcouncil.evaluation.config.EvaluationConfigurationException;
import com.debopam.llmcouncil.evaluation.config.EvaluationInputLoader;
import com.debopam.llmcouncil.evaluation.execution.EvaluationRunner;
import com.debopam.llmcouncil.evaluation.execution.PlanAssessment;
import org.springframework.stereotype.Component;
import com.debopam.llmcouncil.evaluation.judging.HumanReviewExporter;
import com.debopam.llmcouncil.evaluation.storage.EvaluationRunStore;

import java.nio.file.Path;
import java.util.Arrays;

/** Minimal, explicit command parser: unknown flags fail rather than being ignored. */
@Component
public class EvaluationCli {
    private final EvaluationInputLoader loader;
    private final EvaluationRunner runner;
    private final HumanReviewExporter humanReview;
    private final EvaluationRunStore store;

    public EvaluationCli(EvaluationInputLoader loader, EvaluationRunner runner,
                         HumanReviewExporter humanReview, EvaluationRunStore store) {
        this.loader = loader;
        this.runner = runner;
        this.humanReview = humanReview;
        this.store = store;
    }

    public int execute(String[] args) {
        if (args.length == 0 || "help".equals(args[0]) || "--help".equals(args[0]) || "-h".equals(args[0])) {
            help();
            return 0;
        }
        try {
            return switch (args[0]) {
                case "plan" -> plan(args);
                case "run" -> run(args);
                case "resume" -> resume(args);
                case "report" -> report(args);
                case "status" -> status(args);
                case "import-human" -> importHuman(args);
                default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
            };
        } catch (EvaluationConfigurationException ex) {
            System.err.println("Evaluation input is invalid:");
            ex.errors().forEach(error -> System.err.println("  - " + error));
            return 2;
        } catch (Exception ex) {
            System.err.println("Evaluation failed: " + safe(ex));
            return 3;
        }
    }

    private int plan(String[] args) {
        requireCount(args, 2, "plan <plan.yml>");
        rejectUnknownFlags(args, 2);
        EvaluationInputLoader.LoadedInputs inputs = loader.load(Path.of(args[1]));
        PlanAssessment assessment = runner.prepare(inputs.bundle()).assessment();
        print(assessment);
        return 0;
    }

    private int run(String[] args) {
        requireAtLeast(args, 2, "run <plan.yml> --confirm-live [--confirm-billable]");
        Flags flags = flags(args, 2);
        EvaluationInputLoader.LoadedInputs inputs = loader.load(Path.of(args[1]));
        EvaluationRunner.RunOutcome outcome = runner.runNew(inputs, flags.live(), flags.billable());
        System.out.println("Evaluation completed: " + outcome.runDirectory());
        System.out.println("Report: " + outcome.runDirectory().resolve("report/report.md"));
        return 0;
    }

    private int resume(String[] args) {
        requireAtLeast(args, 2, "resume <run-directory> --confirm-live [--confirm-billable]");
        Flags flags = flags(args, 2);
        EvaluationRunner.RunOutcome outcome = runner.resume(Path.of(args[1]), flags.live(), flags.billable());
        System.out.println("Evaluation completed: " + outcome.runDirectory());
        System.out.println("Report: " + outcome.runDirectory().resolve("report/report.md"));
        return 0;
    }

    private int report(String[] args) {
        requireCount(args, 2, "report <run-directory>");
        rejectUnknownFlags(args, 2);
        runner.report(Path.of(args[1]));
        System.out.println("Report regenerated: " + Path.of(args[1]).toAbsolutePath().resolve("report/report.md"));
        return 0;
    }

    private int importHuman(String[] args) {
        requireCount(args, 3, "import-human <run-directory> <decisions.json>");
        int imported = humanReview.importDecisions(Path.of(args[1]), Path.of(args[2]));
        runner.report(Path.of(args[1]));
        System.out.println("Imported " + imported + " blinded human decisions and regenerated the report.");
        return 0;
    }

    private int status(String[] args) {
        requireCount(args, 2, "status <run-directory>");
        EvaluationRunStore.RunProgress value = store.progress(Path.of(args[1]));
        System.out.println("State: " + value.state().status() + " — " + value.state().detail());
        System.out.println("Updated: " + value.state().updatedAt());
        System.out.println("Answers: " + value.answers() + "/" + value.expectedAnswers());
        System.out.println("Deterministic-check files: " + value.checkFiles() + "/" + value.answers());
        System.out.println("Judgments: " + value.judgments() + "/up to " + value.maximumJudgments());
        System.out.println("Report available: " + (value.reportAvailable() ? "yes" : "no"));
        return 0;
    }

    private Flags flags(String[] args, int from) {
        boolean live = false;
        boolean billable = false;
        for (int i = from; i < args.length; i++) {
            switch (args[i]) {
                case "--confirm-live" -> live = true;
                case "--confirm-billable" -> billable = true;
                default -> throw new IllegalArgumentException("Unknown flag: " + args[i]);
            }
        }
        return new Flags(live, billable);
    }

    private void print(PlanAssessment value) {
        System.out.println("Plan is valid.");
        System.out.println("Cases: " + value.cases() + "; repetitions: " + value.repetitions());
        System.out.println("Answer units: " + value.answerUnits());
        value.variants().forEach(variant -> System.out.printf("  %-24s %d units, %d-%d calls/unit (%s)%n",
                variant.variantId(), variant.units(), variant.minimumCallsPerUnit(),
                variant.maximumCallsPerUnit(), variant.detail()));
        System.out.println("Answer calls: " + value.minimumAnswerCalls() + "-" + value.maximumAnswerCalls());
        System.out.println("Maximum judge calls: " + value.maximumJudgeCalls());
        System.out.println("Maximum estimated protocol calls: " + value.maximumTotalCalls());
        System.out.println("Potentially billable providers: " + (value.billableProviders() ? "yes" : "no"));
        if (!value.warnings().isEmpty()) {
            System.out.println("Warnings:");
            value.warnings().forEach(warning -> System.out.println("  - " + warning));
        }
    }

    private void help() {
        System.out.println("""
                LLM Council Evaluation

                Commands:
                  plan <plan.yml>
                      Validate inputs, query council catalog/health, and estimate calls.

                  run <plan.yml> --confirm-live [--confirm-billable]
                      Start a new atomic, resumable evaluation run.

                  resume <run-directory> --confirm-live [--confirm-billable]
                      Continue only missing units; refuses council catalog drift.

                  report <run-directory>
                      Rebuild Markdown, JSON, and CSV reports without live calls.

                  status <run-directory>
                      Show read-only progress while another process runs the evaluation.

                  import-human <run-directory> <decisions.json>
                      Validate blinded human decisions, preserve them separately, and report.
                """);
    }

    private void requireCount(String[] args, int count, String usage) {
        if (args.length != count) throw new IllegalArgumentException("Usage: " + usage);
    }
    private void requireAtLeast(String[] args, int count, String usage) {
        if (args.length < count) throw new IllegalArgumentException("Usage: " + usage);
    }
    private void rejectUnknownFlags(String[] args, int from) {
        if (args.length > from) throw new IllegalArgumentException("Unknown arguments: " + Arrays.toString(Arrays.copyOfRange(args, from, args.length)));
    }
    private String safe(Throwable value) { return value.getMessage() == null ? value.getClass().getSimpleName() : value.getMessage(); }
    private record Flags(boolean live, boolean billable) {}
}
