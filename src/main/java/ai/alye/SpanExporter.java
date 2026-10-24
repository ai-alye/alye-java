package ai.alye;

import java.util.List;
import java.util.Map;

/** Where finished spans go. Alye ships one that sends to Alye, one that prints, and one for tests. */
public interface SpanExporter {
    /** Send a batch. Return false if it could not be delivered. */
    boolean export(List<Span> spans);

    /** Send a service heartbeat. */
    default boolean sendHeartbeat(Map<String, Object> payload) { return true; }

    default void shutdown() {}
}
