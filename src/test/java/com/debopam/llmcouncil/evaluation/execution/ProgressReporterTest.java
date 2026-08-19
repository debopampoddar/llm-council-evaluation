package com.debopam.llmcouncil.evaluation.execution;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressReporterTest {
    @Test
    void printsOrdinalCompletionDetailsAndReturnsTrackedResult() {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            ProgressReporter reporter = new ProgressReporter(true, 30);
            reporter.started("ANSWER", 2, 36, "case/variant/r1");
            String result = reporter.withHeartbeat("answer case/variant/r1", () -> "ok");
            reporter.completed("ANSWER", 2, 36, "case/variant/r1", "COMPLETED", 1_500, 3);
            assertEquals("ok", result);
        } finally {
            System.setOut(original);
        }
        String output = captured.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("[2/36] START case/variant/r1"));
        assertTrue(output.contains("COMPLETED, 1s, 3 calls"));
    }
}
