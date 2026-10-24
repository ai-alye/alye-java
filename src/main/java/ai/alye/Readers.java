package ai.alye;

import ai.alye.internal.Reflect;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Read requests, responses and stream events of the OpenAI and Anthropic Java clients, by method name. */
final class Readers {
    private Readers() {}

    interface StreamReader {
        void chunk(Object chunk);

        void done();
    }

    static void request(LlmCall call, String family, Object params) {
        call.set("gen_ai.request.temperature", Reflect.dbl(params, "temperature"));
        call.set("gen_ai.request.top_p", Reflect.dbl(params, "topP"));
        Long max = Reflect.num(params, "maxCompletionTokens");
        if (max == null) max = Reflect.num(params, "maxTokens");
        if (max == null) max = Reflect.num(params, "maxOutputTokens");
        call.set("gen_ai.request.max_tokens", max);
    }

    static void response(LlmCall call, String family, String op, Object r) {
        if (r == null) return;
        if ("anthropic".equals(family)) {
            message(call, r);
        } else if ("chat".equals(op)) {
            Object raw = Reflect.get(r, "rawChatCompletion"); // structured outputs
            chatCompletion(call, raw != null ? raw : r);
        } else if ("responses".equals(op)) {
            Object raw = Reflect.get(r, "rawResponse");
            responsesResponse(call, raw != null ? raw : r);
        } else if ("embeddings".equals(op)) {
            call.setResponse(Reflect.str(Reflect.get(r, "model")), null, (String) null);
            Long in = Reflect.num(r, "usage", "promptTokens");
            call.setUsage(in, 0L);
        }
    }

    // ---- OpenAI ------------------------------------------------------------------------------

    private static void chatCompletion(LlmCall call, Object c) {
        List<String> finish = new ArrayList<>();
        for (Object choice : Reflect.list(c, "choices")) {
            String f = Reflect.str(Reflect.get(choice, "finishReason"));
            if (f != null) finish.add(f);
        }
        call.setResponse(Reflect.str(Reflect.get(c, "model")), Reflect.str(Reflect.get(c, "id")), finish);
        chatUsage(call, Reflect.get(c, "usage"));
    }

    private static void chatUsage(LlmCall call, Object u) {
        if (u == null) return;
        call.setUsage(Reflect.num(u, "promptTokens"), Reflect.num(u, "completionTokens"),
                Reflect.num(u, "promptTokensDetails", "cachedTokens"), null, Reflect.num(u, "completionTokensDetails", "reasoningTokens"));
    }

    private static void responsesResponse(LlmCall call, Object r) {
        String status = Reflect.str(Reflect.get(r, "status"));
        call.setResponse(Reflect.str(Reflect.get(r, "model")), Reflect.str(Reflect.get(r, "id")), status);
        Object u = Reflect.get(r, "usage");
        if (u != null) {
            call.setUsage(Reflect.num(u, "inputTokens"), Reflect.num(u, "outputTokens"),
                    Reflect.num(u, "inputTokensDetails", "cachedTokens"), null, Reflect.num(u, "outputTokensDetails", "reasoningTokens"));
        }
    }

    /** Streamed chat completions only report usage when asked; ask, unless the caller set stream options. */
    static Object withStreamUsage(Object params) {
        if (params == null || Reflect.get(params, "streamOptions") != null) return params;
        try {
            Object builder = params.getClass().getMethod("toBuilder").invoke(params);
            Method setter = null;
            for (Method m : builder.getClass().getMethods()) {
                if ("streamOptions".equals(m.getName()) && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].getSimpleName().equals("ChatCompletionStreamOptions")) {
                    setter = m;
                    break;
                }
            }
            if (setter == null) return params;
            Class<?> optsClass = setter.getParameterTypes()[0];
            Object optsBuilder = optsClass.getMethod("builder").invoke(null);
            optsBuilder.getClass().getMethod("includeUsage", boolean.class).invoke(optsBuilder, true);
            Object opts = optsBuilder.getClass().getMethod("build").invoke(optsBuilder);
            setter.invoke(builder, opts);
            return builder.getClass().getMethod("build").invoke(builder);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return params;
        }
    }

    // ---- Anthropic ---------------------------------------------------------------------------

    private static void message(LlmCall call, Object m) {
        call.setResponse(Reflect.str(Reflect.get(m, "model")), Reflect.str(Reflect.get(m, "id")), Reflect.str(Reflect.get(m, "stopReason")));
        anthropicUsage(call, Reflect.get(m, "usage"), null);
    }

    /** Input as Alye counts it: uncached + cache reads + cache writes. */
    private static void anthropicUsage(LlmCall call, Object u, Long outputOverride) {
        if (u == null) return;
        long base = orZero(Reflect.num(u, "inputTokens"));
        Long read = Reflect.num(u, "cacheReadInputTokens");
        Long write = Reflect.num(u, "cacheCreationInputTokens");
        Long out = outputOverride != null ? outputOverride : Reflect.num(u, "outputTokens");
        call.setUsage(base + orZero(read) + orZero(write), out, read, write, null);
    }

    private static long orZero(Long v) { return v == null ? 0 : v; }

    // ---- streams -----------------------------------------------------------------------------

    static StreamReader stream(LlmCall call, String family, String op) {
        if ("anthropic".equals(family)) return anthropicStream(call);
        if ("responses".equals(op)) return responsesStream(call);
        return chatStream(call);
    }

    private static StreamReader chatStream(LlmCall call) {
        return new StreamReader() {
            String model;
            String id;
            Object usage;
            final Map<Long, String> finish = new TreeMap<>();

            @Override
            public void chunk(Object ch) {
                if (model == null) model = Reflect.str(Reflect.get(ch, "model"));
                if (id == null) id = Reflect.str(Reflect.get(ch, "id"));
                Object u = Reflect.get(ch, "usage");
                if (u != null) usage = u;
                for (Object c : Reflect.list(ch, "choices")) {
                    String f = Reflect.str(Reflect.get(c, "finishReason"));
                    if (f != null) {
                        Long i = Reflect.num(c, "index");
                        finish.put(i == null ? 0L : i, f);
                    }
                }
            }

            @Override
            public void done() {
                call.setResponse(model, id, new ArrayList<>(finish.values()));
                chatUsage(call, usage);
            }
        };
    }

    private static StreamReader responsesStream(LlmCall call) {
        return new StreamReader() {
            Object last;

            @Override
            public void chunk(Object ev) {
                Object resp = Reflect.path(ev, "completed", "response");
                if (resp == null) resp = Reflect.path(ev, "incomplete", "response");
                if (resp == null) resp = Reflect.path(ev, "failed", "response");
                if (resp != null) last = resp;
            }

            @Override
            public void done() {
                if (last != null) responsesResponse(call, last);
            }
        };
    }

    private static StreamReader anthropicStream(LlmCall call) {
        return new StreamReader() {
            Object startUsage;
            Long output;
            String model;
            String id;
            String stop;

            @Override
            public void chunk(Object ev) {
                Object start = Reflect.path(ev, "messageStart", "message");
                if (start != null) {
                    model = Reflect.str(Reflect.get(start, "model"));
                    id = Reflect.str(Reflect.get(start, "id"));
                    startUsage = Reflect.get(start, "usage");
                }
                Object delta = Reflect.get(ev, "messageDelta");
                if (delta != null) {
                    Long out = Reflect.num(delta, "usage", "outputTokens");
                    if (out != null) output = out;
                    String s = Reflect.str(Reflect.path(delta, "delta", "stopReason"));
                    if (s != null) stop = s;
                }
            }

            @Override
            public void done() {
                call.setResponse(model, id, stop);
                anthropicUsage(call, startUsage, output);
            }
        };
    }
}
