package ai.alye;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Keeps finished spans in memory. For tests. */
public final class InMemoryExporter implements SpanExporter {
    private final List<Span> spans = new ArrayList<>();
    private final List<Map<String, Object>> heartbeats = new ArrayList<>();

    @Override
    public synchronized boolean export(List<Span> batch) {
        spans.addAll(batch);
        return true;
    }

    @Override
    public synchronized boolean sendHeartbeat(Map<String, Object> payload) {
        heartbeats.add(payload);
        return true;
    }

    public synchronized List<Span> spans() { return new ArrayList<>(spans); }

    /** Model-call spans only. */
    public synchronized List<Span> llmCalls() {
        List<Span> out = new ArrayList<>();
        for (Span s : spans) if (s.attribute("gen_ai.operation.name") != null) out.add(s);
        return out;
    }

    public synchronized List<Map<String, Object>> heartbeats() { return new ArrayList<>(heartbeats); }

    public synchronized void clear() {
        spans.clear();
        heartbeats.clear();
    }
}
