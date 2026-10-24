package ai.alye;

/** Creates spans with the current parent and tags of this thread, and hands finished ones to the processor. */
final class Tracer {
    private static final ThreadLocal<Span> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Tags> TAGS = new ThreadLocal<>();

    final Config config;
    final BatchProcessor processor;

    Tracer(Config config, BatchProcessor processor) {
        this.config = config;
        this.processor = processor;
    }

    static Span current() { return CURRENT.get(); }

    static Tags currentTags() { return TAGS.get(); }

    static Scope makeCurrent(Span span) {
        Span previous = CURRENT.get();
        CURRENT.set(span);
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    static Scope withTags(Tags tags) {
        Tags previous = TAGS.get();
        TAGS.set(previous == null ? tags : previous.merge(tags));
        return () -> {
            if (previous == null) TAGS.remove();
            else TAGS.set(previous);
        };
    }

    Span startSpan(String name) {
        Span s = new Span(this, name, 1, CURRENT.get());
        Tags t = TAGS.get();
        if (t != null) t.applyTo(s);
        return s;
    }

    LlmCall startCall(String provider, String operation, String model) {
        LlmCall c = new LlmCall(this, provider, operation, model, CURRENT.get());
        Tags t = TAGS.get();
        if (t != null) t.applyTo(c);
        return c;
    }

    void finished(Span span) {
        processor.add(span);
    }
}
