package ai.alye;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** A periodic heartbeat, so Alye can tell a quiet service from one that has stopped reporting. */
final class Heartbeat {
    private static final Logger LOG = Logger.getLogger("ai.alye");

    private final ScheduledExecutorService timer;

    Heartbeat(SpanExporter exporter, Supplier<Map<String, Object>> payload, long intervalMs) {
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "alye-heartbeat");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleAtFixedRate(() -> {
            try {
                exporter.sendHeartbeat(payload.get());
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "alye: heartbeat failed", e);
            }
        }, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    void stop() { timer.shutdownNow(); }

    static Map<String, Object> base(Config cfg, Map<String, String> libraries) {
        Map<String, Object> sdk = new LinkedHashMap<>();
        sdk.put("name", "alye-java");
        sdk.put("language", "java");
        sdk.put("version", Alye.VERSION);
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("java", System.getProperty("java.version"));
        runtime.put("vendor", System.getProperty("java.vendor"));
        runtime.put("platform", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("service", cfg.service);
        p.put("env", cfg.env);
        p.put("release", cfg.release);
        p.put("sdk", sdk);
        p.put("runtime", runtime);
        p.put("host", hostName());
        p.put("pid", ProcessHandle.current().pid());
        p.put("libraries", libraries);
        p.put("started_at", ManagementFactory.getRuntimeMXBean().getStartTime() / 1000.0);
        return p;
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return null;
        }
    }
}
