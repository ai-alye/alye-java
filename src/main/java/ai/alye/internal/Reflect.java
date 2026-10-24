package ai.alye.internal;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads provider response objects by method name, so the SDK needs no compile-time dependency on
 * the OpenAI or Anthropic libraries and keeps working across their versions. Every read returns
 * null instead of throwing when a method is missing or fails.
 */
public final class Reflect {
    private Reflect() {}

    private static final Object MISSING = new Object();
    private static final ConcurrentHashMap<String, Object> METHODS = new ConcurrentHashMap<>();

    /** obj.name(), with Optional unwrapped; null when absent. */
    public static Object get(Object obj, String name) {
        if (obj == null) return null;
        Object m = METHODS.computeIfAbsent(obj.getClass().getName() + "#" + name, k -> find(obj.getClass(), name));
        if (m == MISSING) return null;
        try {
            Object v = ((Method) m).invoke(obj);
            return v instanceof Optional ? ((Optional<?>) v).orElse(null) : v;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Follow a chain of getters: path(obj, "usage", "promptTokens"). */
    public static Object path(Object obj, String... names) {
        Object cur = obj;
        for (String n : names) {
            cur = get(cur, n);
            if (cur == null) return null;
        }
        return cur;
    }

    public static Long num(Object obj, String... names) {
        Object v = path(obj, names);
        return v instanceof Number ? ((Number) v).longValue() : null;
    }

    public static Double dbl(Object obj, String... names) {
        Object v = path(obj, names);
        return v instanceof Number ? ((Number) v).doubleValue() : null;
    }

    /** A string for model names and enum-like values (ChatModel, FinishReason, StopReason, unions). */
    public static String str(Object v) {
        if (v == null) return null;
        if (v instanceof String) return (String) v;
        if (v instanceof Enum) return ((Enum<?>) v).name().toLowerCase();
        for (String m : new String[] {"asString", "value"}) {
            Object s = get(v, m);
            if (s instanceof String) return (String) s;
        }
        for (String m : new String[] {"string", "chat", "only"}) { // union types such as ResponsesModel
            Object inner = get(v, m);
            if (inner != null && inner != v) return str(inner);
        }
        String s = v.toString();
        return s.length() > 256 ? s.substring(0, 256) : s;
    }

    public static List<?> list(Object obj, String name) {
        Object v = get(obj, name);
        return v instanceof List ? (List<?>) v : Collections.emptyList();
    }

    private static Object find(Class<?> c, String name) {
        try {
            Method m = c.getMethod(name);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException | RuntimeException e) {
            return MISSING;
        }
    }
}
