package ai.alye;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settings for {@link Alye#init(AlyeOptions)}. Anything not set here is read from the environment
 * (ALYE_API_KEY, ALYE_SERVICE, ALYE_ENV, ALYE_ENDPOINT, ALYE_EXPORTER, ALYE_RELEASE, ALYE_REGION,
 * ALYE_DISABLED, ALYE_DEBUG), the same names the Python and Node.js SDKs use.
 *
 * <pre>{@code
 * Alye.init(AlyeOptions.builder().service("support-reply").env("production").build());
 * }</pre>
 */
public final class AlyeOptions {
    final String apiKey;
    final String service;
    final String env;
    final String endpoint;
    final Object exporter; // "otlp" | "console" | "none" | SpanExporter
    final String release;
    final String region;
    final Boolean disabled;
    final Boolean debug;
    final Duration flushInterval;
    final Duration heartbeatInterval;
    final Duration timeout;
    final int maxQueue;
    final int maxBatch;
    final boolean shutdownHook;
    final Map<String, Object> resourceAttributes;

    private AlyeOptions(Builder b) {
        apiKey = b.apiKey;
        service = b.service;
        env = b.env;
        endpoint = b.endpoint;
        exporter = b.exporter;
        release = b.release;
        region = b.region;
        disabled = b.disabled;
        debug = b.debug;
        flushInterval = b.flushInterval;
        heartbeatInterval = b.heartbeatInterval;
        timeout = b.timeout;
        maxQueue = b.maxQueue;
        maxBatch = b.maxBatch;
        shutdownHook = b.shutdownHook;
        resourceAttributes = new LinkedHashMap<>(b.resourceAttributes);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String apiKey;
        private String service;
        private String env;
        private String endpoint;
        private Object exporter;
        private String release;
        private String region;
        private Boolean disabled;
        private Boolean debug;
        private Duration flushInterval = Duration.ofSeconds(2);
        private Duration heartbeatInterval = Duration.ofSeconds(60);
        private Duration timeout = Duration.ofSeconds(5);
        private int maxQueue = 10_000;
        private int maxBatch = 512;
        private boolean shutdownHook = true;
        private final Map<String, Object> resourceAttributes = new LinkedHashMap<>();

        /** Your SDK key. Prefer the ALYE_API_KEY environment variable over putting it in code. */
        public Builder apiKey(String v) { apiKey = v; return this; }
        /** One name per microservice, e.g. support-reply. */
        public Builder service(String v) { service = v; return this; }
        /** production, staging, ... Default production. */
        public Builder env(String v) { env = v; return this; }
        /** Only if you run your own Alye server. Default https://api.alye.ai/ingest. */
        public Builder endpoint(String v) { endpoint = v; return this; }
        /** "otlp" (default), "console" or "none". */
        public Builder exporter(String v) { exporter = v; return this; }
        /** Your own exporter, e.g. {@link InMemoryExporter} in tests. */
        public Builder exporter(SpanExporter v) { exporter = v; return this; }
        /** Your app's version or commit. */
        public Builder release(String v) { release = v; return this; }
        /** Cloud region your calls run in (us-east-1, ...), for regional prices. */
        public Builder region(String v) { region = v; return this; }
        public Builder disabled(boolean v) { disabled = v; return this; }
        public Builder debug(boolean v) { debug = v; return this; }
        public Builder flushInterval(Duration v) { flushInterval = v; return this; }
        /** Zero turns heartbeats off. */
        public Builder heartbeatInterval(Duration v) { heartbeatInterval = v; return this; }
        public Builder timeout(Duration v) { timeout = v; return this; }
        public Builder maxQueue(int v) { maxQueue = v; return this; }
        public Builder maxBatch(int v) { maxBatch = v; return this; }
        /** Send queued calls when the JVM exits. Default on. */
        public Builder shutdownHook(boolean v) { shutdownHook = v; return this; }
        public Builder resourceAttribute(String key, Object value) { resourceAttributes.put(key, value); return this; }

        public AlyeOptions build() { return new AlyeOptions(this); }
    }
}
