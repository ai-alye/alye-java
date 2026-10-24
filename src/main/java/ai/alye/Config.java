package ai.alye;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

/** Settings resolved from {@link AlyeOptions} and the environment. */
final class Config {
    static final String DEFAULT_ENDPOINT = "https://api.alye.ai/ingest";

    final String apiKey;
    final String service;
    final String env;
    final String endpoint;
    final Object exporter;
    final String release;
    final String region;
    final boolean disabled;
    final boolean debug;
    final Duration flushInterval;
    final Duration heartbeatInterval;
    final Duration timeout;
    final int maxQueue;
    final int maxBatch;
    final boolean shutdownHook;
    final Map<String, Object> resourceAttributes;

    Config(AlyeOptions o, Function<String, String> env) {
        apiKey = pick(o.apiKey, env.apply("ALYE_API_KEY"));
        String svc = pick(o.service, pick(env.apply("ALYE_SERVICE"), env.apply("OTEL_SERVICE_NAME")));
        service = svc != null ? svc : "unknown_service";
        String e = pick(o.env, env.apply("ALYE_ENV"));
        this.env = e != null ? e : "production";
        endpoint = pick(o.endpoint, env.apply("ALYE_ENDPOINT"));
        exporter = o.exporter != null ? o.exporter : pick(null, env.apply("ALYE_EXPORTER"));
        release = pick(o.release, env.apply("ALYE_RELEASE"));
        region = pick(o.region, env.apply("ALYE_REGION"));
        disabled = o.disabled != null ? o.disabled : truthy(env.apply("ALYE_DISABLED"));
        debug = o.debug != null ? o.debug : truthy(env.apply("ALYE_DEBUG"));
        flushInterval = o.flushInterval;
        heartbeatInterval = o.heartbeatInterval;
        timeout = o.timeout;
        maxQueue = o.maxQueue;
        maxBatch = o.maxBatch;
        shutdownHook = o.shutdownHook;
        resourceAttributes = o.resourceAttributes;
    }

    private static String pick(String a, String b) {
        if (a != null && !a.trim().isEmpty()) return a.trim();
        return b != null && !b.trim().isEmpty() ? b.trim() : null;
    }

    private static boolean truthy(String v) {
        return v != null && ("1".equals(v.trim()) || "true".equalsIgnoreCase(v.trim()) || "yes".equalsIgnoreCase(v.trim()));
    }
}
