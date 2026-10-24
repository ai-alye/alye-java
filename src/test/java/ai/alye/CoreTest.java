package ai.alye;

import ai.alye.internal.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CoreTest {
    @AfterEach
    void tearDown() { Alye.shutdown(); }

    @Test
    void manualCallsForOtherProviders() {
        InMemoryExporter spans = new InMemoryExporter();
        Alye.init(AlyeOptions.builder().exporter(spans).heartbeatInterval(Duration.ZERO).shutdownHook(false).build());
        try (LlmCall call = Alye.llmCall("voyage", "voyage-3", "embeddings")) {
            call.setUsage(42L, 0L);
        }
        LlmCall failed = Alye.llmCall("groq", "llama-4");
        failed.fail(new IllegalStateException("boom"));
        failed.end();
        LlmCall noUsage = Alye.llmCall("mistral_ai", "mistral-large");
        noUsage.end();
        Alye.flush();
        assertEquals(3, spans.llmCalls().size());
        Span voyage = named(spans, "embeddings voyage-3");
        assertEquals(42L, voyage.attribute("gen_ai.usage.input_tokens"));
        Span groq = named(spans, "chat llama-4");
        assertEquals("error", groq.status());
        assertEquals("IllegalStateException", groq.attribute("error.type"));
        assertEquals(true, named(spans, "chat mistral-large").attribute("alye.usage_unknown"));
    }

    /** Spans can arrive in any order (flush and the background thread both send). */
    static Span named(InMemoryExporter spans, String name) {
        return spans.spans().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow(() -> new AssertionError("no span " + name));
    }

    @Test
    void notInitializedOrDisabledRecordsNothing() {
        try (LlmCall call = Alye.llmCall("openai", "gpt-5-mini")) {
            call.setUsage(1L, 1L);
        }
        assertFalse(Alye.isEnabled());
        Alye.init(AlyeOptions.builder().disabled(true).build());
        assertFalse(Alye.isEnabled());
        assertTrue(Alye.flush());
    }

    @Test
    void sendsOtlpJsonAndHeartbeatsToIngest() throws Exception {
        try (FakeServer ingest = new FakeServer()) {
            Alye.init(AlyeOptions.builder().apiKey("alye_live_test").service("billing-bot").env("staging").release("1.4.2")
                    .endpoint(ingest.url() + "/ingest").heartbeatInterval(Duration.ofHours(1)).shutdownHook(false).build());
            try (LlmCall call = Alye.llmCall("openai", "gpt-5-mini")) {
                call.setUsage(10L, 2L);
            }
            assertTrue(Alye.flush(Duration.ofSeconds(5)));
            Alye.shutdown();
            FakeServer.Request traces = ingest.requests.stream().filter(r -> r.path.equals("/ingest/v1/traces")).findFirst().orElseThrow();
            FakeServer.Request hb = ingest.requests.stream().filter(r -> r.path.equals("/ingest/v1/heartbeat")).findFirst().orElseThrow();
            assertEquals("alye_live_test", traces.header("x-api-key"));
            assertEquals("gzip", traces.header("Content-Encoding"));
            // No HTTP/2 upgrade on plain http:// (servers like uvicorn drop the body when asked).
            assertNull(traces.header("Upgrade"));
            assertTrue(traces.header("User-Agent").startsWith("alye-java/" + Alye.VERSION));
            assertTrue(traces.body.contains("{\"key\":\"service.name\",\"value\":{\"stringValue\":\"billing-bot\"}}"), traces.body);
            assertTrue(traces.body.contains("{\"key\":\"deployment.environment.name\",\"value\":{\"stringValue\":\"staging\"}}"));
            assertTrue(traces.body.contains("{\"key\":\"telemetry.sdk.language\",\"value\":{\"stringValue\":\"java\"}}"));
            assertTrue(traces.body.contains("{\"key\":\"gen_ai.usage.input_tokens\",\"value\":{\"intValue\":\"10\"}}"));
            assertTrue(traces.body.contains("\"kind\":3"));
            assertTrue(hb.body.contains("\"service\":\"billing-bot\"") && hb.body.contains("\"name\":\"alye-java\"") && hb.body.contains("\"language\":\"java\""), hb.body);
            assertTrue(hb.body.contains("\"spans_dropped\":0"));
        }
    }

    @Test
    void consoleWithoutApiKey() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ConsoleExporter console = new ConsoleExporter(new PrintStream(buf, true));
        Alye.init(AlyeOptions.builder().exporter(console).heartbeatInterval(Duration.ZERO).shutdownHook(false).build());
        try (Scope s = Alye.context(Tags.customer("acme"))) {
            try (LlmCall call = Alye.llmCall("openai", "gpt-5-mini")) {
                call.setUsage(5L, 1L);
            }
        }
        Alye.flush();
        String line = buf.toString();
        assertTrue(line.startsWith("[alye] chat gpt-5-mini in=5 out=1 "), line);
        assertTrue(line.contains("ok customer=acme"), line);
    }

    @Test
    void jsonEscaping() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("s", "a\"b\\c\nd\u0001");
        m.put("n", 1L);
        m.put("d", 0.5);
        m.put("b", true);
        m.put("l", List.of(1, "x"));
        m.put("z", null);
        assertEquals("{\"s\":\"a\\\"b\\\\c\\nd\\u0001\",\"n\":1,\"d\":0.5,\"b\":true,\"l\":[1,\"x\"],\"z\":null}", Json.write(m));
    }

    @Test
    void versionIsFilledIn() {
        assertTrue(Alye.VERSION.matches("\\d+\\.\\d+\\.\\d+.*"), Alye.VERSION);
    }
}
