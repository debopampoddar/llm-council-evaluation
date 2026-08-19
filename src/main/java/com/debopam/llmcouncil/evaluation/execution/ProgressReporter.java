package com.debopam.llmcouncil.evaluation.execution;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Human-readable console progress with heartbeats for long synchronous model calls. */
@Component
public class ProgressReporter {
    private final boolean enabled;
    private final int heartbeatSeconds;

    public ProgressReporter(
            @Value("${evaluation.progress.enabled:true}") boolean enabled,
            @Value("${evaluation.progress.heartbeat-seconds:30}") int heartbeatSeconds) {
        this.enabled = enabled;
        this.heartbeatSeconds = Math.max(5, heartbeatSeconds);
    }

    public void phase(String message) {
        line("PHASE", message);
    }

    public void info(String message) {
        line("INFO", message);
    }

    public void started(String kind, long current, long total, String unit) {
        line(kind, position(current, total) + " START " + unit);
    }

    public void skipped(String kind, long current, long total, String unit, String reason) {
        line(kind, position(current, total) + " SKIP  " + unit + " — " + reason);
    }

    public void completed(String kind, long current, long total, String unit,
                          String status, long durationMs, int calls) {
        line(kind, position(current, total) + " DONE  " + unit + " — " + status
                + ", " + duration(durationMs) + ", " + calls + " calls");
    }

    public <T> T withHeartbeat(String operation, Supplier<T> action) {
        if (!enabled) return action.get();
        Instant started = Instant.now();
        var executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("evaluation-progress-", 0).factory());
        ScheduledFuture<?> heartbeat = executor.scheduleAtFixedRate(
                () -> line("WAIT", operation + " — elapsed "
                        + duration(Duration.between(started, Instant.now()).toMillis())),
                heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
        try {
            return action.get();
        } finally {
            heartbeat.cancel(false);
            executor.shutdownNow();
        }
    }

    private synchronized void line(String kind, String message) {
        if (enabled) System.out.printf("[%s] %-8s %s%n", Instant.now(), kind, message);
    }

    private String position(long current, long total) {
        return "[" + current + "/" + total + "]";
    }

    private String duration(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        if (seconds < 60) return seconds + "s";
        return (seconds / 60) + "m " + (seconds % 60) + "s";
    }
}
