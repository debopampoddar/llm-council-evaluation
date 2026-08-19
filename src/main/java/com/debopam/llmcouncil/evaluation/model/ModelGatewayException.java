package com.debopam.llmcouncil.evaluation.model;

import com.debopam.llmcouncil.evaluation.domain.UsageMetrics;

/** Categorized provider error; attempted calls are retained for honest budgets. */
public class ModelGatewayException extends RuntimeException {
    private final String category;
    private final int attemptedCalls;
    private final boolean retryable;
    private final UsageMetrics usage;

    public ModelGatewayException(String category, String message, int attemptedCalls, boolean retryable) {
        this(category, message, null, attemptedCalls, retryable,
                attemptedUsage(attemptedCalls));
    }

    public ModelGatewayException(String category, String message, Throwable cause,
                                 int attemptedCalls, boolean retryable) {
        this(category, message, cause, attemptedCalls, retryable,
                attemptedUsage(attemptedCalls));
    }

    public ModelGatewayException(String category, String message, Throwable cause,
                                 UsageMetrics usage, boolean retryable) {
        this(category, message, cause, usage == null ? 0 : usage.calls(), retryable,
                usage == null ? UsageMetrics.empty() : usage);
    }

    private ModelGatewayException(String category, String message, Throwable cause,
                                  int attemptedCalls, boolean retryable, UsageMetrics usage) {
        super(message, cause);
        this.category = category;
        this.attemptedCalls = attemptedCalls;
        this.retryable = retryable;
        this.usage = usage;
    }

    public String category() { return category; }
    public int attemptedCalls() { return attemptedCalls; }
    public boolean retryable() { return retryable; }
    public UsageMetrics usage() { return usage; }

    private static UsageMetrics attemptedUsage(int attemptedCalls) {
        return new UsageMetrics(attemptedCalls, 0, 0, null, true, false);
    }
}
