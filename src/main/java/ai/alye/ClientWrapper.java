package ai.alye;

import ai.alye.internal.Reflect;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Spliterator;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Wraps an OpenAI or Anthropic Java client in proxies of its own service interfaces. Calls to
 * chat completions, responses, embeddings and messages (sync, async, streaming) are recorded;
 * everything else passes straight through.
 */
final class ClientWrapper implements InvocationHandler {
    private static final Logger LOG = Logger.getLogger("ai.alye");

    /** Service accessors to follow; their results are wrapped too. */
    private static final Set<String> SERVICES = new HashSet<>(Arrays.asList(
            "chat", "completions", "responses", "embeddings", "messages", "beta", "async", "sync", "withOptions"));

    private final Object target;
    private final String provider;
    private final String family; // openai | anthropic
    private final String path;   // e.g. "chat.completions"

    private ClientWrapper(Object target, String provider, String family, String path) {
        this.target = target;
        this.provider = provider;
        this.family = family;
        this.path = path;
    }

    static <T> T wrap(T client, String provider) {
        if (client == null || Proxy.isProxyClass(client.getClass()) && Proxy.getInvocationHandler(client) instanceof ClientWrapper) return client;
        String family = family(client.getClass());
        if (family == null) {
            LOG.warning("alye: wrap() supports the OpenAI and Anthropic Java clients; " + client.getClass().getName()
                    + " is passed through unrecorded. Use Alye.llmCall() for other providers.");
            return client;
        }
        Class<?>[] ifaces = clientInterfaces(client.getClass(), family);
        if (ifaces.length == 0) return client;
        @SuppressWarnings("unchecked")
        T proxy = (T) Proxy.newProxyInstance(ifaces[0].getClassLoader(), ifaces,
                new ClientWrapper(client, provider != null ? provider : family, family, ""));
        return proxy;
    }

    private static String family(Class<?> c) {
        for (Class<?> i : allInterfaces(c)) {
            if (i.getName().startsWith("com.openai.client.")) return "openai";
            if (i.getName().startsWith("com.anthropic.client.")) return "anthropic";
        }
        return null;
    }

    private static Class<?>[] clientInterfaces(Class<?> c, String family) {
        String prefix = "openai".equals(family) ? "com.openai.client." : "com.anthropic.client.";
        List<Class<?>> out = new ArrayList<>();
        for (Class<?> i : allInterfaces(c)) if (i.getName().startsWith(prefix) && java.lang.reflect.Modifier.isPublic(i.getModifiers())) out.add(i);
        return out.toArray(new Class<?>[0]);
    }

    private static Set<Class<?>> allInterfaces(Class<?> c) {
        Set<Class<?>> out = new LinkedHashSet<>();
        for (Class<?> k = c; k != null; k = k.getSuperclass()) collect(k, out);
        return out;
    }

    private static void collect(Class<?> c, Set<Class<?>> out) {
        for (Class<?> i : c.getInterfaces()) if (out.add(i)) collect(i, out);
    }

    /** What a method at this path records, or null to pass through. */
    private String operation(String method) {
        if (!"create".equals(method) && !"createStreaming".equals(method)) return null;
        String p = path.replaceAll("^(async\\.|sync\\.)+", "").replace(".async", "").replace(".sync", "");
        if ("openai".equals(family)) {
            if (p.equals("chat.completions")) return "chat";
            if (p.equals("responses")) return "responses";
            if (p.equals("embeddings") && "create".equals(method)) return "embeddings";
        } else if (p.equals("messages") || p.equals("beta.messages")) {
            return "chat";
        }
        return null;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (method.getDeclaringClass() == Object.class) {
            if ("equals".equals(name)) return proxy == args[0];
            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
            if ("toString".equals(name)) return "Alye(" + target + ")";
        }
        String op = operation(name);
        if (op != null) {
            Tracer tracer = Alye.tracer();
            if (tracer != null) return record(tracer, op, method, args);
        }
        Object result = call(method, args);
        Class<?> rt = method.getReturnType();
        if (result != null && rt.isInterface() && SERVICES.contains(name) && java.lang.reflect.Modifier.isPublic(rt.getModifiers())) {
            String next = "withOptions".equals(name) ? path : path.isEmpty() ? name : path + "." + name;
            return Proxy.newProxyInstance(rt.getClassLoader(), new Class<?>[] {rt}, new ClientWrapper(result, provider, family, next));
        }
        return result;
    }

    private Object call(Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private Object record(Tracer tracer, String op, Method method, Object[] args) throws Throwable {
        Object params = args != null && args.length > 0 ? args[0] : null;
        boolean streaming = "createStreaming".equals(method.getName());
        LlmCall call = tracer.startCall(provider, op, Reflect.str(Reflect.get(params, "model")));
        try {
            Readers.request(call, family, params);
            if (streaming) call.set("alye.streaming", true);
            if (streaming && "openai".equals(family) && "chat".equals(op)) {
                Object withUsage = Readers.withStreamUsage(params);
                if (withUsage != params && args != null) {
                    args = args.clone();
                    args[0] = withUsage;
                    call.set("alye.stream_usage_injected", true);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            LOG.log(Level.FINE, "alye: could not read the request", e);
        }
        Object result;
        try {
            result = call(method, args);
        } catch (Throwable t) {
            call.fail(t);
            call.end();
            throw t;
        }
        if (result instanceof CompletableFuture) {
            return ((CompletableFuture<?>) result).whenComplete((value, error) -> {
                if (error != null) call.fail(error);
                else read(call, op, value);
                call.end();
            });
        }
        if (streaming) return wrapStream(call, op, method.getReturnType(), result);
        read(call, op, result);
        call.end();
        return result;
    }

    private void read(LlmCall call, String op, Object response) {
        try {
            Readers.response(call, family, op, response);
        } catch (RuntimeException | LinkageError e) {
            LOG.log(Level.FINE, "alye: could not read the response", e);
        }
    }

    // ---- streaming -------------------------------------------------------------------------

    private Object wrapStream(LlmCall call, String op, Class<?> type, Object stream) {
        if (stream == null || !type.isInterface()) {
            call.end();
            return stream;
        }
        Readers.StreamReader reader = Readers.stream(call, family, op);
        Runnable done = () -> {
            try {
                reader.done();
            } catch (RuntimeException | LinkageError e) {
                LOG.log(Level.FINE, "alye: could not read the stream", e);
            }
            call.end();
        };
        Consumer<Object> onChunk = chunk -> {
            try {
                call.markFirstToken();
                reader.chunk(chunk);
            } catch (RuntimeException | LinkageError e) {
                LOG.log(Level.FINE, "alye: could not read a stream chunk", e);
            }
        };
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, m, a) -> {
            switch (m.getName()) {
                case "stream": {
                    // StreamResponse.stream(): read each chunk as the caller consumes it.
                    Object s = invokeOn(stream, m, a);
                    return s instanceof Stream ? observed((Stream<?>) s, onChunk, done) : s;
                }
                case "subscribe": {
                    // AsyncStreamResponse.subscribe(handler[, executor])
                    Object[] next = a.clone();
                    next[0] = observedHandler(m.getParameterTypes()[0], a[0], onChunk, done, call);
                    Object r = invokeOn(stream, m, next);
                    return r == stream ? proxy : r;
                }
                case "close": {
                    try {
                        return invokeOn(stream, m, a);
                    } finally {
                        done.run();
                    }
                }
                default:
                    return invokeOn(stream, m, a);
            }
        });
    }

    private static Object invokeOn(Object target, Method m, Object[] a) throws Throwable {
        try {
            return m.invoke(target, a);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static <T> Stream<T> observed(Stream<T> source, Consumer<Object> onChunk, Runnable done) {
        Spliterator<T> inner = source.spliterator();
        Spliterator<T> outer = new Spliterator<T>() {
            @Override
            public boolean tryAdvance(Consumer<? super T> action) {
                boolean more = inner.tryAdvance(x -> {
                    onChunk.accept(x);
                    action.accept(x);
                });
                if (!more) done.run();
                return more;
            }

            @Override
            public Spliterator<T> trySplit() { return null; }

            @Override
            public long estimateSize() { return inner.estimateSize(); }

            @Override
            public int characteristics() { return inner.characteristics() & ~(SIZED | SUBSIZED); }
        };
        return StreamSupport.stream(outer, false).onClose(() -> {
            source.close();
            done.run();
        });
    }

    private static Object observedHandler(Class<?> type, Object handler, Consumer<Object> onChunk, Runnable done, LlmCall call) {
        if (handler == null || !type.isInterface()) return handler;
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> {
            if ("onNext".equals(m.getName()) && a != null && a.length == 1) onChunk.accept(a[0]);
            if ("onComplete".equals(m.getName()) && a != null && a.length == 1) {
                Object err = a[0] instanceof java.util.Optional ? ((java.util.Optional<?>) a[0]).orElse(null) : a[0];
                if (err instanceof Throwable) call.fail((Throwable) err);
                done.run();
            }
            if (m.getDeclaringClass() == Object.class) {
                if ("equals".equals(m.getName())) return p == a[0];
                if ("hashCode".equals(m.getName())) return System.identityHashCode(p);
                if ("toString".equals(m.getName())) return "Alye(" + handler + ")";
            }
            return invokeOn(handler, m, a);
        });
    }
}
