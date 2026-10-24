package ai.alye;

/** Undoes {@link Alye#context(Tags)} or a current span when closed. Use with try-with-resources. */
public interface Scope extends AutoCloseable {
    @Override
    void close();
}
