package com.debopam.llmcouncil.evaluation.model;

import com.debopam.llmcouncil.evaluation.domain.UsageMetrics;

import java.util.concurrent.ThreadLocalRandom;

/** Bounded transient retry wrapper with exponential backoff and jitter. */
public class RetryingModelGateway implements ModelGateway {
    private final ModelGateway delegate;
    private final int maxRetries;
    private final long baseDelayMs;

    public RetryingModelGateway(ModelGateway delegate, int maxRetries, long baseDelayMs) {
        this.delegate = delegate;
        this.maxRetries = maxRetries;
        this.baseDelayMs = baseDelayMs;
    }

    @Override
    public ModelResponse call(ModelPrompt prompt) {
        UsageMetrics failedUsage = UsageMetrics.empty();
        for (int attempt = 0; ; attempt++) {
            try {
                ModelResponse result = delegate.call(prompt);
                if (failedUsage.calls() == 0) return result;
                return new ModelResponse(result.text(), result.durationMs(),
                        failedUsage.plus(result.usage()));
            } catch (ModelGatewayException ex) {
                failedUsage = failedUsage.plus(ex.usage().calls() == 0
                        ? new UsageMetrics(Math.max(1, ex.attemptedCalls()), 0, 0,
                                null, true, false)
                        : ex.usage());
                if (!ex.retryable() || attempt >= maxRetries) {
                    throw new ModelGatewayException(ex.category(), ex.getMessage(), ex,
                            failedUsage, ex.retryable());
                }
                sleep(backoff(attempt));
            }
        }
    }

    private long backoff(int attempt) {
        long exponential = Math.multiplyExact(baseDelayMs, 1L << Math.min(attempt, 20));
        return exponential + ThreadLocalRandom.current().nextLong(251);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ModelGatewayException("INTERRUPTED", "Retry interrupted", ex, 0, false);
        }
    }
}
