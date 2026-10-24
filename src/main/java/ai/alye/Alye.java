package ai.alye;

import java.io.InputStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Records every LLM call your service makes: model, tokens, cost, latency, errors.
 *
 * <pre>{@code
 * Alye.init(AlyeOptions.builder().service("support-reply").build());   // reads ALYE_API_KEY
 * OpenAIClient openai = Alye.wrap(OpenAIOkHttpClient.fromEnv());
 * }</pre>
 *
 * <p>Calls through a wrapped client are recorded from then on. Until {@code init} runs (or when the
 * SDK is disabled) everything passes straight through.
 */
public final class Alye {
    private static final Logger LOG = Logger.getLogger("ai.alye");

    /** The SDK version. */
    public static final String VERSION = readVersion();

    private static volatile Tracer tracer;
    private static Heartbeat heartbeat;
    private static Thread shutdownHook;

    private Alye() {}

    /** Start with settings from the environment (ALYE_API_KEY, ALYE_SERVICE, ...). */
    public static void init() { init(AlyeOptions.builder().build()); }

    public static synchronized void init(AlyeOptions options) {
        if (tracer != null) shutdown();
        Config cfg = new Config(options, System::getenv);
        if (cfg.debug) Logger.getLogger("ai.alye").setLevel(Level.FINE);
        if (cfg.disabled) {
            LOG.info("alye: disabled; no calls will be recorded");
            return;
        }
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("service.name", cfg.service);
        resource.put("deployment.environment.name", cfg.env);
        resource.put("service.version", cfg.release);
        resource.put("cloud.region", cfg.region);
        resource.put("telemetry.sdk.name", "alye");
        resource.put("telemetry.sdk.language", "java");
        resource.put("telemetry.sdk.version", VERSION);
        resource.putAll(cfg.resourceAttributes);

        SpanExporter exporter = exporter(cfg, resource);
        BatchProcessor processor = new BatchProcessor(exporter, cfg.maxQueue, cfg.maxBatch, cfg.flushInterval);
        tracer = new Tracer(cfg, processor);

        if (!cfg.heartbeatInterval.isZero() && !cfg.heartbeatInterval.isNegative()) {
            Map<String, Object> base = Heartbeat.base(cfg, Libraries.versions());
            Supplier<Map<String, Object>> payload = () -> {
                Map<String, Object> p = new LinkedHashMap<>(base);
                p.put("sent_at", System.currentTimeMillis() / 1000.0);
                p.put("queue_size", processor.queueSize());
                p.put("spans_exported", processor.exported.get());
                p.put("spans_failed", processor.failed.get());
                p.put("spans_dropped", processor.dropped.get());
                return p;
            };
            heartbeat = new Heartbeat(exporter, payload, cfg.heartbeatInterval.toMillis());
        }
        if (cfg.shutdownHook) {
            shutdownHook = new Thread(() -> shutdown(Duration.ofSeconds(2)), "alye-shutdown");
            try {
                Runtime.getRuntime().addShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                shutdownHook = null; // already shutting down
            }
        }
    }

    private static SpanExporter exporter(Config cfg, Map<String, Object> resource) {
        if (cfg.exporter instanceof SpanExporter) return (SpanExporter) cfg.exporter;
        String kind = cfg.exporter == null ? "otlp" : cfg.exporter.toString().toLowerCase();
        if ("none".equals(kind)) return spans -> true;
        if ("console".equals(kind)) return new ConsoleExporter();
        String endpoint = cfg.endpoint;
        if (endpoint == null) {
            if (cfg.apiKey == null) {
                LOG.warning("alye: no API key set (ALYE_API_KEY); printing calls to the console instead of sending them.");
                return new ConsoleExporter();
            }
            endpoint = Config.DEFAULT_ENDPOINT;
        }
        boolean local = endpoint.contains("localhost") || endpoint.contains("127.0.0.1") || endpoint.contains("0.0.0.0");
        if (cfg.apiKey == null && !local) LOG.warning("alye: no API key set (ALYE_API_KEY); calls will not be sent.");
        return new OtlpHttpExporter(endpoint, cfg.apiKey, resource, cfg.timeout);
    }

    static Tracer tracer() { return tracer; }

    public static boolean isEnabled() { return tracer != null; }

    /**
     * Record calls made with an OpenAI or Anthropic Java client. Returns a client of the same type;
     * use it in place of the original. Works before or after {@link #init}.
     */
    public static <T> T wrap(T client) { return ClientWrapper.wrap(client, null); }

    /**
     * Like {@link #wrap(Object)}, naming the provider: "azure.ai.openai" for an Azure OpenAI client,
     * "deepseek", "groq", ... for OpenAI-compatible endpoints, "aws.bedrock" or "gcp.vertex_ai"
     * for Claude through those clouds.
     */
    public static <T> T wrap(T client, String provider) { return ClientWrapper.wrap(client, provider); }

    /**
     * Record a call to a provider without a wrapper. Set the token counts from its response, then
     * close the call (try-with-resources). Failed calls: {@link LlmCall#fail(Throwable)}.
     */
    public static LlmCall llmCall(String provider, String model) { return llmCall(provider, model, "chat"); }

    public static LlmCall llmCall(String provider, String model, String operation) {
        Tracer t = tracer;
        return t != null ? t.startCall(provider, operation, model) : new LlmCall(null, provider, operation, model, null);
    }

    /**
     * Group steps into one trace: model calls made inside become this span's children.
     *
     * <pre>{@code
     * String answer = Alye.span("answer_ticket", () -> agent.run(ticket));
     * }</pre>
     */
    public static <T> T span(String name, Callable<T> work) throws Exception {
        Span s = startSpan(name);
        try (Scope ignored = makeCurrent(s)) {
            return work.call();
        } catch (Exception | Error e) {
            s.fail(e);
            throw e;
        } finally {
            s.end();
        }
    }

    public static void span(String name, Runnable work) {
        Span s = startSpan(name);
        try (Scope ignored = makeCurrent(s)) {
            work.run();
        } catch (RuntimeException | Error e) {
            s.fail(e);
            throw e;
        } finally {
            s.end();
        }
    }

    /** A span you end yourself; pair with {@link #makeCurrent(Span)} so calls inside nest under it. */
    public static Span startSpan(String name) {
        Tracer t = tracer;
        return t != null ? t.startSpan(name) : new Span(null, name, 1, null);
    }

    /** Make a span the parent of calls on this thread until the scope closes. */
    public static Scope makeCurrent(Span span) { return Tracer.makeCurrent(span); }

    /** Tag model calls on this thread (customer, session, labels) until the scope closes. */
    public static Scope context(Tags tags) { return Tracer.withTags(tags); }

    /** Send everything queued so far. Returns false if it didn't finish in time. */
    public static boolean flush(Duration timeout) {
        Tracer t = tracer;
        return t == null || t.processor.flush(timeout);
    }

    public static boolean flush() { return flush(Duration.ofSeconds(5)); }

    /** Send what's queued and stop. Called for you when the JVM exits. */
    public static void shutdown() { shutdown(Duration.ofSeconds(5)); }

    public static synchronized void shutdown(Duration timeout) {
        Tracer t = tracer;
        if (t == null) return;
        tracer = null;
        if (heartbeat != null) {
            heartbeat.stop();
            heartbeat = null;
        }
        t.processor.shutdown(timeout);
        if (shutdownHook != null && Thread.currentThread() != shutdownHook) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // the JVM is exiting
            }
        }
        shutdownHook = null;
    }

    private static String readVersion() {
        try (InputStream in = Alye.class.getResourceAsStream("/ai/alye/version.properties")) {
            Properties p = new Properties();
            if (in != null) p.load(in);
            return p.getProperty("version", "0.0.0");
        } catch (Exception e) {
            return "0.0.0";
        }
    }
}
