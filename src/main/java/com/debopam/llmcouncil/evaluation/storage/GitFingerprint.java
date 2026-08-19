package com.debopam.llmcouncil.evaluation.storage;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Best-effort source fingerprint; evaluation remains usable outside a Git checkout. */
@Component
public class GitFingerprint {
    public Fingerprint read(Path start) {
        String commit = command(start, "rev-parse", "HEAD");
        String status = command(start, "status", "--porcelain");
        return new Fingerprint(commit == null ? "unknown" : commit, status != null && !status.isBlank());
    }

    private String command(Path start, String... arguments) {
        try {
            java.util.List<String> command = new java.util.ArrayList<>();
            command.add("git");
            command.add("-C");
            command.add(start.toAbsolutePath().normalize().toString());
            command.addAll(java.util.List.of(arguments));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(3, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                return null;
            }
            return new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception ex) {
            return null;
        }
    }

    public record Fingerprint(String commit, boolean dirty) {}
}
