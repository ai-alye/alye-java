package ai.alye;

import java.util.Collections;
import java.util.List;

/**
 * One model call. The wrapped OpenAI and Anthropic clients and the LangChain4j listener create
 * these for you; for other providers use {@link Alye#llmCall}:
 *
 * <pre>{@code
 * try (LlmCall call = Alye.llmCall("voyage", "voyage-3", "embeddings")) {
 *     EmbedResult r = voyage.embed(texts);
 *     call.setUsage(r.totalTokens(), 0L);
 * }
 * }</pre>
 */
public final class LlmCall extends Span {
    private boolean usageSet;

    LlmCall(Tracer tracer, String provider, String operation, String model, Span parent) {
        super(tracer, model == null || model.isEmpty() ? operation : operation + " " + model, 3, parent);
        set("gen_ai.provider.name", provider);
        set("gen_ai.operation.name", operation);
        set("gen_ai.request.model", model);
    }

    /** Input and output tokens. Input includes cached tokens. */
    public LlmCall setUsage(Long inputTokens, Long outputTokens) {
        return setUsage(inputTokens, outputTokens, null, null, null);
    }

    /** Token counts from the provider's response; input includes cache reads and writes. */
    public LlmCall setUsage(Long inputTokens, Long outputTokens, Long cacheReadTokens, Long cacheWriteTokens, Long reasoningTokens) {
        if (inputTokens == null && outputTokens == null) return this;
        usageSet = true;
        set("gen_ai.usage.input_tokens", inputTokens);
        set("gen_ai.usage.output_tokens", outputTokens);
        if (cacheReadTokens != null && cacheReadTokens > 0) set("gen_ai.usage.cache_read.input_tokens", cacheReadTokens);
        if (cacheWriteTokens != null && cacheWriteTokens > 0) set("gen_ai.usage.cache_creation.input_tokens", cacheWriteTokens);
        if (reasoningTokens != null && reasoningTokens > 0) set("gen_ai.usage.reasoning.output_tokens", reasoningTokens);
        return this;
    }

    /** The model and response id the provider reported, and why it stopped. */
    public LlmCall setResponse(String model, String responseId, List<String> finishReasons) {
        set("gen_ai.response.model", model);
        set("gen_ai.response.id", responseId);
        if (finishReasons != null && !finishReasons.isEmpty()) set("gen_ai.response.finish_reasons", finishReasons);
        return this;
    }

    public LlmCall setResponse(String model, String responseId, String finishReason) {
        return setResponse(model, responseId, finishReason == null ? null : Collections.singletonList(finishReason));
    }

    /** Time to the first streamed token, in seconds since the call started. */
    public LlmCall markFirstToken() {
        if (attribute("gen_ai.response.time_to_first_chunk") == null) set("gen_ai.response.time_to_first_chunk", durationMs() / 1000.0);
        return this;
    }

    @Override
    public void end() {
        if (!usageSet && !"error".equals(status)) set("alye.usage_unknown", true);
        super.end();
    }
}
