package ai.alye;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.StreamResponse;
import com.openai.errors.RateLimitException;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.StructuredChatCompletion;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.openai.models.embeddings.EmbeddingModel;
import com.openai.models.responses.ResponseCreateParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiTest {
    FakeServer server;
    InMemoryExporter spans;
    OpenAIClient openai;

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeServer();
        spans = new InMemoryExporter();
        Alye.init(AlyeOptions.builder().service("support-reply").exporter(spans).heartbeatInterval(Duration.ZERO).shutdownHook(false).build());
        openai = Alye.wrap(OpenAIOkHttpClient.builder().apiKey("sk-test").baseUrl(server.url() + "/v1").maxRetries(0).build());
    }

    @AfterEach
    void tearDown() {
        Alye.shutdown();
        server.close();
    }

    private Span one() {
        Alye.flush();
        List<Span> calls = spans.llmCalls();
        assertEquals(1, calls.size(), "expected one LLM call, got " + calls.size());
        return calls.get(0);
    }

    private static ChatCompletionCreateParams params() {
        return ChatCompletionCreateParams.builder().model(ChatModel.GPT_5_MINI).addUserMessage("Classify: card charged twice").temperature(0.2).build();
    }

    @Test
    void chatCompletion() {
        ChatCompletion res = openai.chat().completions().create(params());
        assertEquals("Hello", res.choices().get(0).message().content().orElse(null));
        Span s = one();
        assertEquals("chat gpt-5-mini", s.name());
        assertEquals("openai", s.attribute("gen_ai.provider.name"));
        assertEquals("chat", s.attribute("gen_ai.operation.name"));
        assertEquals("gpt-5-mini", s.attribute("gen_ai.request.model"));
        assertEquals("gpt-5-mini-2026", s.attribute("gen_ai.response.model"));
        assertEquals(120L, s.attribute("gen_ai.usage.input_tokens"));
        assertEquals(30L, s.attribute("gen_ai.usage.output_tokens"));
        assertEquals(100L, s.attribute("gen_ai.usage.cache_read.input_tokens"));
        assertEquals(12L, s.attribute("gen_ai.usage.reasoning.output_tokens"));
        assertEquals(List.of("stop"), s.attribute("gen_ai.response.finish_reasons"));
        assertEquals(0.2, s.attribute("gen_ai.request.temperature"));
        assertEquals("ok", s.status());
    }

    @Test
    void streamingAsksForUsage() {
        String text;
        try (StreamResponse<ChatCompletionChunk> stream = openai.chat().completions().createStreaming(params())) {
            text = stream.stream().flatMap(c -> c.choices().stream()).map(c -> c.delta().content().orElse("")).collect(Collectors.joining());
        }
        assertEquals("Hello", text);
        assertTrue(server.requests.get(0).body.contains("\"include_usage\":true"), "usage requested");
        Span s = one();
        assertEquals(12L, s.attribute("gen_ai.usage.input_tokens"));
        assertEquals(5L, s.attribute("gen_ai.usage.output_tokens"));
        assertEquals(true, s.attribute("alye.streaming"));
        assertNotNull(s.attribute("gen_ai.response.time_to_first_chunk"));
        assertEquals(List.of("stop"), s.attribute("gen_ai.response.finish_reasons"));
    }

    @Test
    void asyncClient() throws Exception {
        ChatCompletion res = openai.async().chat().completions().create(params()).get();
        assertEquals("c1", res.id());
        assertEquals(120L, one().attribute("gen_ai.usage.input_tokens"));
    }

    @Test
    void structuredOutputs() {
        StructuredChatCompletion<Answer> res = openai.chat().completions().create(
                ChatCompletionCreateParams.builder().model(ChatModel.GPT_5_MINI).addUserMessage("Capital of France?").responseFormat(Answer.class).build());
        assertEquals("Paris", res.choices().get(0).message().content().get().answer);
        assertEquals(120L, one().attribute("gen_ai.usage.input_tokens"));
    }

    static class Answer {
        public String answer;
    }

    @Test
    void responsesAndEmbeddings() {
        openai.responses().create(ResponseCreateParams.builder().model(ChatModel.GPT_5_MINI).input("Hi").build());
        openai.embeddings().create(EmbeddingCreateParams.builder().model(EmbeddingModel.TEXT_EMBEDDING_3_SMALL).input("hello").build());
        Alye.flush();
        assertEquals(2, spans.llmCalls().size());
        Span r = CoreTest.named(spans, "responses gpt-5-mini");
        assertEquals(9L, r.attribute("gen_ai.usage.input_tokens"));
        assertEquals(3L, r.attribute("gen_ai.usage.output_tokens"));
        assertEquals(List.of("completed"), r.attribute("gen_ai.response.finish_reasons"));
        Span e = CoreTest.named(spans, "embeddings text-embedding-3-small");
        assertEquals(8L, e.attribute("gen_ai.usage.input_tokens"));
    }

    @Test
    void failuresAreRecordedAndRethrown() {
        server.failures.put("/v1/chat/completions", 429);
        assertThrows(RateLimitException.class, () -> openai.chat().completions().create(params()));
        Span s = one();
        assertEquals("error", s.status());
        assertEquals("rate_limited", s.attribute("error.type"));
        assertEquals(429L, s.attribute("http.response.status_code"));
    }

    @Test
    void passesThroughBeforeInitAndNamesTheProvider() {
        Alye.shutdown();
        assertEquals("c1", openai.chat().completions().create(params()).id());
        assertTrue(spans.spans().isEmpty());

        Alye.init(AlyeOptions.builder().exporter(spans).heartbeatInterval(Duration.ZERO).shutdownHook(false).build());
        OpenAIClient deepseek = Alye.wrap(OpenAIOkHttpClient.builder().apiKey("sk").baseUrl(server.url() + "/v1").maxRetries(0).build(), "deepseek");
        deepseek.chat().completions().create(params());
        assertEquals("deepseek", one().attribute("gen_ai.provider.name"));
        assertSame(deepseek, Alye.wrap(deepseek), "wrapping twice returns the same client");
    }

    @Test
    void customerTagsAndTraces() throws Exception {
        String answer = Alye.span("answer_ticket", () -> {
            try (Scope s = Alye.context(Tags.customer("northwind").session("t-1").tag("feature", "refunds"))) {
                return openai.chat().completions().create(params()).id();
            }
        });
        assertEquals("c1", answer);
        Alye.flush();
        Span call = spans.llmCalls().get(0);
        Span root = spans.spans().stream().filter(x -> x.name().equals("answer_ticket")).findFirst().orElseThrow();
        assertEquals("northwind", call.attribute("alye.customer_id"));
        assertEquals("t-1", call.attribute("session.id"));
        assertEquals("refunds", call.attribute("alye.tag.feature"));
        assertEquals(root.traceId(), call.traceId());
        assertEquals(root.spanId(), call.parentSpanId());
        assertNull(root.parentSpanId());
    }
}
