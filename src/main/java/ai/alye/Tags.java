package ai.alye;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Who and what a model call is for: your customer's id (an account or tenant id, not a name or
 * email), a session id and your own labels. Apply with {@link Alye#context(Tags)}:
 *
 * <pre>{@code
 * try (Scope s = Alye.context(Tags.customer("northwind").session(ticket.id()).tag("feature", "refunds"))) {
 *     answer(ticket);
 * }
 * }</pre>
 */
public final class Tags {
    final String customerId;
    final String sessionId;
    final Map<String, Object> labels;

    private Tags(String customerId, String sessionId, Map<String, Object> labels) {
        this.customerId = customerId;
        this.sessionId = sessionId;
        this.labels = labels;
    }

    public static Tags empty() { return new Tags(null, null, Collections.emptyMap()); }

    public static Tags customer(String customerId) { return empty().withCustomer(customerId); }

    public Tags withCustomer(String customerId) { return new Tags(customerId, sessionId, labels); }

    public Tags session(String sessionId) { return new Tags(customerId, sessionId, labels); }

    /** A label such as feature=refunds. Values: String, Number or Boolean. */
    public Tags tag(String key, Object value) {
        Map<String, Object> next = new LinkedHashMap<>(labels);
        next.put(key, value);
        return new Tags(customerId, sessionId, Collections.unmodifiableMap(next));
    }

    /** These tags with {@code inner}'s on top (inner wins). */
    Tags merge(Tags inner) {
        if (inner == null) return this;
        Map<String, Object> next = new LinkedHashMap<>(labels);
        next.putAll(inner.labels);
        return new Tags(inner.customerId != null ? inner.customerId : customerId, inner.sessionId != null ? inner.sessionId : sessionId,
                Collections.unmodifiableMap(next));
    }

    void applyTo(Span span) {
        span.set("alye.customer_id", customerId);
        span.set("session.id", sessionId);
        for (Map.Entry<String, Object> e : labels.entrySet()) span.set("alye.tag." + e.getKey(), e.getValue());
    }
}
