package ai.alye;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Queues finished spans without blocking the caller and sends them in batches from a daemon
 * thread. When the queue is full new spans are dropped and counted (reported in the heartbeat)
 * rather than slowing your service down.
 */
final class BatchProcessor {
    private static final Logger LOG = Logger.getLogger("ai.alye");

    private final SpanExporter exporter;
    private final BlockingQueue<Span> queue;
    private final int maxBatch;
    private final long flushIntervalMs;
    private final Object exportLock = new Object();
    private final Thread worker;
    private volatile boolean running = true;
    final AtomicLong exported = new AtomicLong();
    final AtomicLong failed = new AtomicLong();
    final AtomicLong dropped = new AtomicLong();
    /** Spans accepted and not yet sent (queued, or in a batch being built or sent). */
    private final AtomicInteger pending = new AtomicInteger();

    BatchProcessor(SpanExporter exporter, int maxQueue, int maxBatch, Duration flushInterval) {
        this.exporter = exporter;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, maxQueue));
        this.maxBatch = Math.max(1, maxBatch);
        this.flushIntervalMs = Math.max(10, flushInterval.toMillis());
        this.worker = new Thread(this::run, "alye-exporter");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    void add(Span span) {
        pending.incrementAndGet();
        if (!running || !queue.offer(span)) {
            pending.decrementAndGet();
            dropped.incrementAndGet();
        }
    }

    int queueSize() { return queue.size(); }

    private void run() {
        while (running) {
            try {
                Span first = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                List<Span> batch = new ArrayList<>(maxBatch);
                batch.add(first);
                // Give a burst a moment to fill the batch.
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.min(flushIntervalMs, 200));
                while (batch.size() < maxBatch) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) break;
                    Span s = queue.poll(left, TimeUnit.NANOSECONDS);
                    if (s == null) break;
                    batch.add(s);
                }
                send(batch);
            } catch (InterruptedException e) {
                if (!running) return;
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "alye: export failed", e);
            }
        }
    }

    private void send(List<Span> batch) {
        synchronized (exportLock) {
            boolean ok;
            try {
                ok = exporter.export(batch);
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "alye: exporter threw", e);
                ok = false;
            }
            (ok ? exported : failed).addAndGet(batch.size());
            pending.addAndGet(-batch.size());
        }
    }

    /** Send everything queued so far, on the calling thread, and wait for batches already on their way. */
    boolean flush(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!queue.isEmpty()) {
            if (System.nanoTime() > deadline) return false;
            List<Span> batch = new ArrayList<>(maxBatch);
            queue.drainTo(batch, maxBatch);
            if (!batch.isEmpty()) send(batch);
        }
        while (pending.get() > 0) {
            if (System.nanoTime() > deadline) return false;
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    void shutdown(Duration timeout) {
        flush(timeout);
        running = false;
        worker.interrupt();
        try {
            exporter.shutdown();
        } catch (RuntimeException ignored) {
            // shutting down anyway
        }
    }
}
