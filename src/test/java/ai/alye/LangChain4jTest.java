package ai.alye;

import ai.alye.langchain4j.AlyeChatModelListener;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LangChain4jTest {
    FakeServer server;
    InMemoryExporter spans;

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeServer();
        spans = new InMemoryExporter();
        Alye.init(AlyeOptions.builder().exporter(spans).heartbeatInterval(Duration.ZERO).shutdownHook(false).build());
    }

    @AfterEach
    void tearDown() {
        Alye.shutdown();
        server.close();
    }

    private ChatModel model() {
        return OpenAiChatModel.builder().baseUrl(server.url() + "/v1").apiKey("sk-test").modelName("gpt-5-mini").maxRetries(0)
                .listeners(List.of(new AlyeChatModelListener())).build();
    }

    @Test
    void recordsChatCalls() {
        String answer;
        try (Scope s = Alye.context(Tags.customer("northwind"))) {
            answer = model().chat("Hi");
        }
        assertEquals("Hello", answer);
        Alye.flush();
        List<Span> calls = spans.llmCalls();
        assertEquals(1, calls.size());
        Span c = calls.get(0);
        assertEquals("openai", c.attribute("gen_ai.provider.name"));
        assertEquals("gpt-5-mini", c.attribute("gen_ai.request.model"));
        assertEquals("gpt-5-mini-2026", c.attribute("gen_ai.response.model"));
        assertEquals(120L, c.attribute("gen_ai.usage.input_tokens"));
        assertEquals(30L, c.attribute("gen_ai.usage.output_tokens"));
        assertEquals("northwind", c.attribute("alye.customer_id"));
        assertEquals(List.of("stop"), c.attribute("gen_ai.response.finish_reasons"));
    }

    @Test
    void recordsFailures() {
        server.failures.put("/v1/chat/completions", 429);
        assertThrows(RuntimeException.class, () -> model().chat("Hi"));
        Alye.flush();
        Span c = spans.llmCalls().get(0);
        assertEquals("error", c.status());
        assertEquals("rate_limited", c.attribute("error.type"));
    }
}
