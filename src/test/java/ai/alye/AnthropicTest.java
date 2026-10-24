package ai.alye;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.RawMessageStreamEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicTest {
    FakeServer server;
    InMemoryExporter spans;
    AnthropicClient claude;

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeServer();
        spans = new InMemoryExporter();
        Alye.init(AlyeOptions.builder().service("support-reply").exporter(spans).heartbeatInterval(Duration.ZERO).shutdownHook(false).build());
        claude = Alye.wrap(AnthropicOkHttpClient.builder().apiKey("sk-ant-test").baseUrl(server.url()).maxRetries(0).build());
    }

    @AfterEach
    void tearDown() {
        Alye.shutdown();
        server.close();
    }

    private static MessageCreateParams params() {
        return MessageCreateParams.builder().model(Model.of("claude-sonnet-5-5")).maxTokens(500).addUserMessage("hi").build();
    }

    private Span one() {
        Alye.flush();
        List<Span> calls = spans.llmCalls();
        assertEquals(1, calls.size());
        return calls.get(0);
    }

    @Test
    void messagesCreateCountsCachedInput() {
        Message m = claude.messages().create(params());
        assertEquals("m1", m.id());
        Span s = one();
        assertEquals("anthropic", s.attribute("gen_ai.provider.name"));
        assertEquals("chat claude-sonnet-5-5", s.name());
        // total input = uncached 20 + cache read 300 + cache write 50
        assertEquals(370L, s.attribute("gen_ai.usage.input_tokens"));
        assertEquals(300L, s.attribute("gen_ai.usage.cache_read.input_tokens"));
        assertEquals(50L, s.attribute("gen_ai.usage.cache_creation.input_tokens"));
        assertEquals(15L, s.attribute("gen_ai.usage.output_tokens"));
        assertEquals(List.of("end_turn"), s.attribute("gen_ai.response.finish_reasons"));
        assertEquals(500L, s.attribute("gen_ai.request.max_tokens"));
    }

    @Test
    void streaming() {
        long events;
        try (StreamResponse<RawMessageStreamEvent> stream = claude.messages().createStreaming(params())) {
            events = stream.stream().count();
        }
        assertEquals(6, events);
        Span s = one();
        assertEquals(112L, s.attribute("gen_ai.usage.input_tokens"));
        assertEquals(7L, s.attribute("gen_ai.usage.output_tokens"));
        assertEquals(true, s.attribute("alye.streaming"));
        assertEquals(List.of("end_turn"), s.attribute("gen_ai.response.finish_reasons"));
    }

    @Test
    void asyncAndBeta() throws Exception {
        claude.async().messages().create(params()).get();
        claude.beta().messages().create(com.anthropic.models.beta.messages.MessageCreateParams.builder()
                .model(Model.of("claude-sonnet-5-5")).maxTokens(10).addUserMessage("hi").build());
        Alye.flush();
        assertEquals(2, spans.llmCalls().size());
        for (Span s : spans.llmCalls()) assertEquals(15L, s.attribute("gen_ai.usage.output_tokens"));
    }

    @Test
    void failures() {
        server.failures.put("/v1/messages", 429);
        assertThrows(RateLimitException.class, () -> claude.messages().create(params()));
        Span s = one();
        assertEquals("rate_limited", s.attribute("error.type"));
        assertNull(s.attribute("alye.usage_unknown"), "failed calls aren't marked usage-unknown");
    }
}
