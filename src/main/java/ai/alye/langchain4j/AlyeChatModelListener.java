package ai.alye.langchain4j;

import ai.alye.Alye;
import ai.alye.LlmCall;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

/**
 * Records every call a LangChain4j chat model makes. Add it to the model's listeners:
 *
 * <pre>{@code
 * ChatModel model = OpenAiChatModel.builder()
 *         .apiKey(System.getenv("OPENAI_API_KEY"))
 *         .modelName("gpt-5-mini")
 *         .listeners(List.of(new AlyeChatModelListener()))
 *         .build();
 * }</pre>
 *
 * Works for chat and streaming chat models of every LangChain4j provider.
 */
public final class AlyeChatModelListener implements ChatModelListener {
    private static final Object KEY = new Object();

    @Override
    public void onRequest(ChatModelRequestContext ctx) {
        try {
            ChatRequest req = ctx.chatRequest();
            String model = req != null ? req.modelName() : null;
            LlmCall call = Alye.llmCall(provider(ctx.modelProvider()), model, "chat");
            if (req != null) {
                call.set("gen_ai.request.temperature", req.temperature());
                call.set("gen_ai.request.top_p", req.topP());
                Integer max = req.maxOutputTokens();
                call.set("gen_ai.request.max_tokens", max == null ? null : max.longValue());
            }
            ctx.attributes().put(KEY, call);
        } catch (RuntimeException | LinkageError ignored) {
            // never break the model call
        }
    }

    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        Object v = ctx.attributes().remove(KEY);
        if (!(v instanceof LlmCall)) return;
        LlmCall call = (LlmCall) v;
        try {
            ChatResponse res = ctx.chatResponse();
            if (res != null) {
                call.setResponse(res.modelName(), res.id(), res.finishReason() == null ? null : res.finishReason().name().toLowerCase());
                TokenUsage u = res.tokenUsage();
                if (u != null) {
                    call.setUsage(u.inputTokenCount() == null ? null : u.inputTokenCount().longValue(),
                            u.outputTokenCount() == null ? null : u.outputTokenCount().longValue());
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // record what we have
        }
        call.end();
    }

    @Override
    public void onError(ChatModelErrorContext ctx) {
        Object v = ctx.attributes().remove(KEY);
        if (!(v instanceof LlmCall)) return;
        LlmCall call = (LlmCall) v;
        call.fail(ctx.error());
        call.end();
    }

    /** LangChain4j's provider names -> the names Alye prices with. */
    static String provider(ModelProvider p) {
        if (p == null) return "unknown";
        switch (p.name()) {
            case "OPEN_AI": return "openai";
            case "AZURE_OPEN_AI": return "azure.ai.openai";
            case "ANTHROPIC": return "anthropic";
            case "GOOGLE_AI_GEMINI":
            case "GOOGLE_GENAI": return "gcp.gemini";
            case "GOOGLE_VERTEX_AI_GEMINI":
            case "GOOGLE_VERTEX_AI_ANTHROPIC": return "gcp.vertex_ai";
            case "AMAZON_BEDROCK": return "aws.bedrock";
            case "MISTRAL_AI": return "mistral_ai";
            case "GITHUB_MODELS": return "github_models";
            case "MICROSOFT_FOUNDRY": return "azure.ai.inference";
            default: return p.name().toLowerCase();
        }
    }
}
