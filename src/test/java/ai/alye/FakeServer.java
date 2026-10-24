package ai.alye;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/** A local stand-in for the OpenAI and Anthropic APIs, and for Alye's ingest. */
final class FakeServer implements AutoCloseable {
    final HttpServer server;
    final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    /** Path -> HTTP status to fail with (e.g. 429). */
    final Map<String, Integer> failures = new ConcurrentHashMap<>();

    static final class Request {
        final String path;
        final String body;
        final Map<String, List<String>> headers;

        Request(String path, String body, Map<String, List<String>> headers) {
            this.path = path;
            this.body = body;
            this.headers = headers;
        }

        String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) if (e.getKey().equalsIgnoreCase(name)) return e.getValue().get(0);
            return null;
        }
    }

    FakeServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private void handle(HttpExchange ex) throws IOException {
        byte[] raw = readAll(ex.getRequestBody());
        String enc = ex.getRequestHeaders().getFirst("Content-Encoding");
        String body = new String("gzip".equalsIgnoreCase(enc) ? readAll(new GZIPInputStream(new java.io.ByteArrayInputStream(raw))) : raw, StandardCharsets.UTF_8);
        String path = ex.getRequestURI().getPath();
        requests.add(new Request(path, body, ex.getRequestHeaders()));
        Integer fail = failures.get(path);
        if (fail != null) {
            send(ex, fail, "application/json", "{\"error\":{\"message\":\"Rate limit reached\",\"type\":\"rate_limit_error\"}}");
            return;
        }
        boolean stream = body.replace(" ", "").contains("\"stream\":true");
        if (path.endsWith("/chat/completions")) {
            if (stream) {
                boolean usage = body.replace(" ", "").contains("\"include_usage\":true");
                StringBuilder sse = new StringBuilder();
                sse.append(sse("{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-5-mini-2026\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Hel\"},\"finish_reason\":null}]}"));
                sse.append(sse("{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-5-mini-2026\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"lo\"},\"finish_reason\":\"stop\"}]}"));
                if (usage) sse.append(sse("{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-5-mini-2026\",\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":5,\"total_tokens\":17}}"));
                sse.append("data: [DONE]\n\n");
                send(ex, 200, "text/event-stream", sse.toString());
            } else {
                String content = body.contains("\"response_format\"") ? "{\\\"answer\\\":\\\"Paris\\\"}" : "Hello";
                send(ex, 200, "application/json", "{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"gpt-5-mini-2026\","
                        + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\",\"refusal\":null},\"finish_reason\":\"stop\",\"logprobs\":null}],"
                        + "\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":30,\"total_tokens\":150,\"prompt_tokens_details\":{\"cached_tokens\":100},\"completion_tokens_details\":{\"reasoning_tokens\":12}}}");
            }
        } else if (path.endsWith("/responses")) {
            send(ex, 200, "application/json", "{\"id\":\"r1\",\"object\":\"response\",\"created_at\":1,\"model\":\"gpt-5-mini\",\"status\":\"completed\","
                    + "\"output\":[{\"type\":\"message\",\"id\":\"o1\",\"role\":\"assistant\",\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hi\",\"annotations\":[]}]}],"
                    + "\"parallel_tool_calls\":true,\"tool_choice\":\"auto\",\"tools\":[],\"error\":null,\"incomplete_details\":null,\"instructions\":null,\"metadata\":{},\"temperature\":1,\"top_p\":1,"
                    + "\"usage\":{\"input_tokens\":9,\"output_tokens\":3,\"total_tokens\":12,\"input_tokens_details\":{\"cached_tokens\":0},\"output_tokens_details\":{\"reasoning_tokens\":0}}}");
        } else if (path.endsWith("/embeddings")) {
            send(ex, 200, "application/json", "{\"object\":\"list\",\"model\":\"text-embedding-3-small\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[0.1,0.2]}],"
                    + "\"usage\":{\"prompt_tokens\":8,\"total_tokens\":8}}");
        } else if (path.endsWith("/messages")) {
            if (stream) {
                StringBuilder sse = new StringBuilder();
                sse.append(named("message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet-5-5\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":12,\"output_tokens\":1,\"cache_read_input_tokens\":100,\"cache_creation_input_tokens\":0}}}"));
                sse.append(named("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"));
                sse.append(named("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}"));
                sse.append(named("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"));
                sse.append(named("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":7}}"));
                sse.append(named("message_stop", "{\"type\":\"message_stop\"}"));
                send(ex, 200, "text/event-stream", sse.toString());
            } else {
                send(ex, 200, "application/json", "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet-5-5\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"Refund issued.\"}],\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
                        + "\"usage\":{\"input_tokens\":20,\"output_tokens\":15,\"cache_read_input_tokens\":300,\"cache_creation_input_tokens\":50}}");
            }
        } else if (path.startsWith("/ingest/")) {
            send(ex, 200, "application/json", "{}");
        } else {
            send(ex, 404, "application/json", "{}");
        }
    }

    private static String sse(String data) { return "data: " + data + "\n\n"; }

    private static String named(String event, String data) { return "event: " + event + "\ndata: " + data + "\n\n"; }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        in.transferTo(out);
        return out.toByteArray();
    }

    @Override
    public void close() { server.stop(0); }
}
