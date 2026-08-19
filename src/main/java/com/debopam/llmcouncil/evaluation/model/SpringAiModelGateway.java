package com.debopam.llmcouncil.evaluation.model;

import com.debopam.llmcouncil.evaluation.domain.EvaluationPlan;
import com.debopam.llmcouncil.evaluation.domain.UsageMetrics;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Spring AI adapter shared by OpenAI, Anthropic, and Gemini. */
public class SpringAiModelGateway implements ModelGateway {
    private final EvaluationPlan.ModelSpec model;
    private final ChatClient client;
    private final boolean includeTemperature;

    public SpringAiModelGateway(EvaluationPlan.ModelSpec model, ChatClient client,
                                boolean includeTemperature) {
        this.model = model;
        this.client = client;
        this.includeTemperature = includeTemperature;
    }

    @Override
    public ModelResponse call(ModelPrompt prompt) {
        Instant started = Instant.now();
        try {
            ChatOptions.Builder options = ChatOptions.builder()
                    .model(model.providerModelId()).maxTokens(model.maxOutputTokens());
            if (includeTemperature) options.temperature(model.temperature());
            var request = client.prompt().system(prompt.system()).user(prompt.user()).options(options.build());
            FutureTask<Result> task = new FutureTask<>(() -> {
                var responseSpec = request.call();
                String content = responseSpec.content();
                return new Result(content, responseSpec.chatResponse());
            });
            Thread.startVirtualThread(task);
            Result result;
            try {
                result = task.get(model.timeoutSeconds(), TimeUnit.SECONDS);
            } catch (TimeoutException ex) {
                task.cancel(true);
                throw new ModelGatewayException("MODEL_TIMEOUT", "Model call timed out", ex, 1, true);
            }
            Long input = null;
            Long output = null;
            try {
                if (result.response() != null && result.response().getMetadata() != null
                        && result.response().getMetadata().getUsage() != null) {
                    Integer promptTokens = result.response().getMetadata().getUsage().getPromptTokens();
                    Integer completionTokens = result.response().getMetadata().getUsage().getCompletionTokens();
                    input = promptTokens == null ? null : promptTokens.longValue();
                    output = completionTokens == null ? null : completionTokens.longValue();
                }
            } catch (RuntimeException ignored) {
                // Usage is best effort; the missing-data flag remains honest.
            }
            return new ModelResponse(result.content(), Duration.between(started, Instant.now()).toMillis(),
                    usage(input, output));
        } catch (ModelGatewayException ex) {
            throw ex;
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw new ModelGatewayException("MODEL_CALL_FAILED", safe(cause), cause, 1, transientFailure(cause));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ModelGatewayException("INTERRUPTED", "Model call interrupted", ex, 1, false);
        } catch (RuntimeException ex) {
            throw new ModelGatewayException("MODEL_CALL_FAILED", safe(ex), ex, 1, transientFailure(ex));
        }
    }

    private UsageMetrics usage(Long prompt, Long completion) {
        long input = prompt == null ? 0 : prompt;
        long output = completion == null ? 0 : completion;
        boolean priced = value(model.costPer1kInputTokens()) > 0 || value(model.costPer1kOutputTokens()) > 0;
        Double cost = priced ? Math.round((input / 1000.0 * value(model.costPer1kInputTokens())
                + output / 1000.0 * value(model.costPer1kOutputTokens())) * 1_000_000.0) / 1_000_000.0 : null;
        boolean partiallyPriced = (prompt == null && value(model.costPer1kInputTokens()) > 0)
                || (completion == null && value(model.costPer1kOutputTokens()) > 0);
        return new UsageMetrics(1, input, output, cost, prompt == null || completion == null, partiallyPriced);
    }

    private boolean transientFailure(Throwable value) {
        String name = value.getClass().getName().toLowerCase();
        String message = safe(value).toLowerCase();
        return name.contains("timeout") || message.contains("timeout") || message.contains("429")
                || message.contains("503") || message.contains("temporar");
    }
    private String safe(Throwable value) { return value.getMessage() == null ? value.getClass().getSimpleName() : value.getMessage(); }
    private double value(Double value) { return value == null ? 0 : value; }
    private record Result(String content, ChatResponse response) {}
}
