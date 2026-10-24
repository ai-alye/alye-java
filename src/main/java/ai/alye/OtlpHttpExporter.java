package ai.alye;

import ai.alye.internal.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

/** Sends spans as OTLP/HTTP JSON to {@code {endpoint}/v1/traces}, gzip-compressed, with the SDK key. */
public final class OtlpHttpExporter implements SpanExporter {
    private static final Logger LOG = Logger.getLogger("ai.alye");

    private final String endpoint;
    private final String apiKey;
    private final Map<String, Object> resource;
    private final Duration timeout;
    private final int maxAttempts;
    private final HttpClient http;
    private volatile boolean keyRejectedLogged;

    public OtlpHttpExporter(String endpoint, String apiKey, Map<String, Object> resource, Duration timeout) {
        this.endpoint = endpoint.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.resource = resource;
        this.timeout = timeout;
        this.maxAttempts = 3;
        // HTTP/1.1: on plain http:// the JDK client otherwise tries an h2c upgrade, which some servers
        // (uvicorn among them) answer by dropping the request body.
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build();
    }

    @Override
    public boolean export(List<Span> spans) {
        List<Map<String, Object>> otlp = new ArrayList<>(spans.size());
        for (Span s : spans) otlp.add(s.toOtlp());
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("name", "alye");
        scope.put("version", Alye.VERSION);
        Map<String, Object> scopeSpans = new LinkedHashMap<>();
        scopeSpans.put("scope", scope);
        scopeSpans.put("spans", otlp);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("attributes", Otlp.attributes(resource));
        Map<String, Object> rs = new LinkedHashMap<>();
        rs.put("resource", res);
        rs.put("scopeSpans", Collections.singletonList(scopeSpans));
        return post("/v1/traces", Collections.singletonMap("resourceSpans", Collections.singletonList(rs)));
    }

    @Override
    public boolean sendHeartbeat(Map<String, Object> payload) {
        return post("/v1/heartbeat", payload);
    }

    private boolean post(String path, Object body) {
        byte[] data;
        try {
            data = gzip(Json.write(body).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(endpoint + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Content-Encoding", "gzip")
                .header("User-Agent", "alye-java/" + Alye.VERSION + " java/" + System.getProperty("java.version"))
                .POST(HttpRequest.BodyPublishers.ofByteArray(data));
        if (apiKey != null && !apiKey.isEmpty()) req.header("x-api-key", apiKey);
        HttpRequest request = req.build();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Double wait = null;
            try {
                HttpResponse<Void> res = http.send(request, HttpResponse.BodyHandlers.discarding());
                int code = res.statusCode();
                if (code < 300) return true;
                if (code == 401 || code == 403) {
                    if (!keyRejectedLogged) {
                        keyRejectedLogged = true;
                        LOG.severe("alye: the ingest endpoint rejected the API key (HTTP " + code + "). Check ALYE_API_KEY.");
                    }
                    return false;
                }
                if (code != 408 && code != 429 && code < 500) {
                    LOG.warning("alye: ingest returned HTTP " + code + "; dropping " + path);
                    return false;
                }
                wait = res.headers().firstValue("Retry-After").map(OtlpHttpExporter::seconds).orElse(null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (IOException e) {
                LOG.log(Level.FINE, "alye: send to " + endpoint + path + " failed", e);
            }
            if (attempt < maxAttempts) {
                double s = wait != null ? wait : Math.min(8.0, 0.5 * Math.pow(2, attempt - 1)) * (0.5 + ThreadLocalRandom.current().nextDouble());
                try {
                    Thread.sleep((long) (Math.min(s, 30.0) * 1000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        LOG.warning("alye: could not reach " + endpoint + path + " after " + maxAttempts + " attempts");
        return false;
    }

    private static Double seconds(String header) {
        try {
            return Math.max(0, Double.parseDouble(header.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 4 + 64);
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(raw);
        }
        return out.toByteArray();
    }
}
