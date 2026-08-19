package com.debopam.llmcouncil.evaluation.model;

/** Categorized provider error; attempted calls are retained for honest budgets. */
public class ModelGatewayException extends RuntimeException {
    private final String category;
    private final int attemptedCalls;
    private final boolean retryable;

    public ModelGatewayException(String category, String message, int attemptedCalls, boolean retryable) {
        super(message);
        this.category = category;
        this.attemptedCalls = attemptedCalls;
        this.retryable = retryable;
    }

    public ModelGatewayException(String category, String message, Throwable cause,
                                 int attemptedCalls, boolean retryable) {
        super(message, cause);
        this.category = category;
        this.attemptedCalls = attemptedCalls;
        this.retryable = retryable;
    }

    public String category() { return category; }
    public int attemptedCalls() { return attemptedCalls; }
    public boolean retryable() { return retryable; }
}
