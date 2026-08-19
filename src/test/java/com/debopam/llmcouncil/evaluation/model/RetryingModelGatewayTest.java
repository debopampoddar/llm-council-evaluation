package com.debopam.llmcouncil.evaluation.model;

import com.debopam.llmcouncil.evaluation.domain.UsageMetrics;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetryingModelGatewayTest {
    @Test
    void countsFailedAttemptsBeforeASuccessfulRetry() {
        AtomicInteger attempts = new AtomicInteger();
        ModelGateway delegate = prompt -> {
            if (attempts.getAndIncrement() < 2)
                throw new ModelGatewayException("PROVIDER_UNAVAILABLE", "retry", 1, true);
            return new ModelResponse("ok", 1, new UsageMetrics(1, 3, 2, 0.01, false, false));
        };

        ModelResponse response = new RetryingModelGateway(delegate, 2, 0)
                .call(new ModelPrompt("id", "system", "user", false));

        assertEquals(3, response.usage().calls());
        assertEquals(3, attempts.get());
    }

    @Test
    void doesNotRetryPermanentFailures() {
        AtomicInteger attempts = new AtomicInteger();
        ModelGateway delegate = prompt -> {
            attempts.incrementAndGet();
            throw new ModelGatewayException("MODEL_NOT_FOUND", "missing", 1, false);
        };

        ModelGatewayException failure = assertThrows(ModelGatewayException.class,
                () -> new RetryingModelGateway(delegate, 3, 0)
                        .call(new ModelPrompt("id", "system", "user", false)));

        assertEquals(1, failure.attemptedCalls());
        assertEquals(1, attempts.get());
    }
}
