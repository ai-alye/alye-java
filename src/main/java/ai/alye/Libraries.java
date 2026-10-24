package ai.alye;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** Versions of the AI libraries on the classpath, reported in the heartbeat. */
final class Libraries {
    private Libraries() {}

    // name, a class in the library, its Maven coordinates (for jars without a manifest version)
    private static final String[][] KNOWN = {
        {"openai", "com.openai.client.OpenAIClient", "com.openai/openai-java-core"},
        {"anthropic", "com.anthropic.client.AnthropicClient", "com.anthropic/anthropic-java-core"},
        {"langchain4j", "dev.langchain4j.model.chat.ChatModel", "dev.langchain4j/langchain4j-core"},
    };

    static Map<String, String> versions() {
        Map<String, String> out = new LinkedHashMap<>();
        ClassLoader loader = Libraries.class.getClassLoader();
        for (String[] k : KNOWN) {
            Class<?> c;
            try {
                c = Class.forName(k[1], false, loader);
            } catch (ClassNotFoundException | LinkageError e) {
                continue;
            }
            String v = c.getPackage() != null ? c.getPackage().getImplementationVersion() : null;
            if (v == null) v = pomVersion(c, k[2]);
            out.put(k[0], v != null ? v : "unknown");
        }
        return out;
    }

    private static String pomVersion(Class<?> c, String coords) {
        try (InputStream in = c.getResourceAsStream("/META-INF/maven/" + coords + "/pom.properties")) {
            if (in == null) return null;
            Properties p = new Properties();
            p.load(in);
            return p.getProperty("version");
        } catch (Exception e) {
            return null;
        }
    }
}
