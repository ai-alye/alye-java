package ai.alye;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One step of work: an agent run, a tool call or a model call. Close it (or use try-with-resources)
 * to send it. Model calls are {@link LlmCall}s; group them under a span to see one run as one trace.
 */
public class Span implements AutoCloseable {
    private static final SecureRandom RANDOM = new SecureRandom();

    final String traceId;
    final String spanId;
    final String parentSpanId;
    final String name;
    final int kind; // OTLP: 1 internal, 3 client
    final long startEpochNanos;
    private final long startNanoTime;
    private long endEpochNanos;
    final Map<String, Object> attributes = new LinkedHashMap<>();
    private final List<Map<String, Object>> events = new ArrayList<>();
    String status = "unset";
    private String statusMessage;
    private boolean ended;
    private final Tracer tracer;
    Runnable onEnd;

    Span(Tracer tracer, String name, int kind, Span parent) {
        this.tracer = tracer;
        this.name = name;
        this.kind = kind;
        this.traceId = parent != null ? parent.traceId : hex(16);
        this.parentSpanId = parent != null ? parent.spanId : null;
        this.spanId = hex(8);
        Instant now = Instant.now();
        this.startEpochNanos = now.getEpochSecond() * 1_000_000_000L + now.getNano();
        this.startNanoTime = System.nanoTime();
    }

    static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** Set an attribute. Null values are ignored. */
    public synchronized Span set(String key, Object value) {
        if (value != null && !ended) attributes.put(key, value);
        return this;
    }

    public String traceId() { return traceId; }
    public String spanId() { return spanId; }
    /** The parent span's id, or null for a root span. */
    public String parentSpanId() { return parentSpanId; }
    public String name() { return name; }
    /** unset, ok or error. */
    public synchronized String status() { return status; }

    public synchronized Object attribute(String key) { return attributes.get(key); }

    /** A copy of the attributes recorded so far. */
    public synchronized Map<String, Object> attributes() { return new LinkedHashMap<>(attributes); }

    /** Mark the span failed. Called for you on model-call errors. */
    public synchronized Span fail(Throwable error) {
        if (ended || error == null) return this;
        String type = ErrorTypes.classify(error);
        status = "error";
        statusMessage = truncate(String.valueOf(error.getMessage()), 1000);
        attributes.put("error.type", type);
        Integer http = ErrorTypes.statusCode(error);
        if (http != null) attributes.put("http.response.status_code", http.longValue());
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("name", "exception");
        ev.put("timeUnixNano", String.valueOf(nowEpochNanos()));
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("exception.type", error.getClass().getName());
        a.put("exception.message", truncate(String.valueOf(error.getMessage()), 1000));
        ev.put("attributes", Otlp.attributes(a));
        events.add(ev);
        return this;
    }

    /** Mark the span failed with your own error type, e.g. "invalid_output". */
    public synchronized Span fail(String errorType, String message) {
        if (ended) return this;
        status = "error";
        statusMessage = message == null ? null : truncate(message, 1000);
        attributes.put("error.type", errorType);
        return this;
    }

    public double durationMs() {
        long end = endEpochNanos != 0 ? endEpochNanos : nowEpochNanos();
        return (end - startEpochNanos) / 1e6;
    }

    private long nowEpochNanos() {
        return startEpochNanos + (System.nanoTime() - startNanoTime);
    }

    /** End the span and queue it for sending. Safe to call more than once. */
    public void end() {
        synchronized (this) {
            if (ended) return;
            endEpochNanos = nowEpochNanos();
            if ("unset".equals(status)) status = "ok";
            ended = true;
        }
        Runnable r = onEnd;
        if (r != null) {
            try { r.run(); } catch (RuntimeException ignored) { /* never break the caller */ }
        }
        if (tracer != null) tracer.finished(this);
    }

    @Override
    public void close() { end(); }

    synchronized Map<String, Object> toOtlp() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("traceId", traceId);
        out.put("spanId", spanId);
        if (parentSpanId != null) out.put("parentSpanId", parentSpanId);
        out.put("name", name);
        out.put("kind", kind);
        out.put("startTimeUnixNano", String.valueOf(startEpochNanos));
        out.put("endTimeUnixNano", String.valueOf(endEpochNanos != 0 ? endEpochNanos : nowEpochNanos()));
        out.put("attributes", Otlp.attributes(attributes));
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("code", "error".equals(status) ? 2 : "ok".equals(status) ? 1 : 0);
        if (statusMessage != null) st.put("message", statusMessage);
        out.put("status", st);
        if (!events.isEmpty()) out.put("events", events);
        return out;
    }

    static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
