package ai.alye;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OTLP/HTTP JSON encoding of attribute values. */
final class Otlp {
    private Otlp() {}

    static List<Map<String, Object>> attributes(Map<String, Object> attrs) {
        List<Map<String, Object>> out = new ArrayList<>(attrs.size());
        for (Map.Entry<String, Object> e : attrs.entrySet()) {
            if (e.getValue() == null) continue;
            Map<String, Object> kv = new LinkedHashMap<>();
            kv.put("key", e.getKey());
            kv.put("value", value(e.getValue()));
            out.add(kv);
        }
        return out;
    }

    static Map<String, Object> value(Object v) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (v instanceof Boolean) {
            out.put("boolValue", v);
        } else if (v instanceof Double || v instanceof Float) {
            out.put("doubleValue", ((Number) v).doubleValue());
        } else if (v instanceof Number) {
            out.put("intValue", String.valueOf(((Number) v).longValue()));
        } else if (v instanceof Collection) {
            List<Object> values = new ArrayList<>();
            for (Object x : (Collection<?>) v) values.add(value(x));
            Map<String, Object> arr = new LinkedHashMap<>();
            arr.put("values", values);
            out.put("arrayValue", arr);
        } else {
            out.put("stringValue", String.valueOf(v));
        }
        return out;
    }
}
