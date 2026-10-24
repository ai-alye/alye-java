package ai.alye;

import java.io.PrintStream;
import java.util.List;

/** Prints one line per model call. Used when no API key is set; handy in local development. */
public final class ConsoleExporter implements SpanExporter {
    private final PrintStream out;

    public ConsoleExporter() { this(System.err); }

    public ConsoleExporter(PrintStream out) { this.out = out; }

    @Override
    public boolean export(List<Span> spans) {
        for (Span s : spans) {
            Object op = s.attribute("gen_ai.operation.name");
            if (op == null && s.parentSpanId != null) continue;
            Object model = s.attribute("gen_ai.response.model") != null ? s.attribute("gen_ai.response.model") : s.attribute("gen_ai.request.model");
            Object in = s.attribute("gen_ai.usage.input_tokens");
            Object outTok = s.attribute("gen_ai.usage.output_tokens");
            String tokens = in != null || outTok != null ? " in=" + (in == null ? "?" : in) + " out=" + (outTok == null ? "?" : outTok) : "";
            String status = "error".equals(s.status) ? "ERROR " + s.attribute("error.type") : "ok";
            Object customer = s.attribute("alye.customer_id");
            out.printf("[alye] %s%s%s %.0fms %s%s%n", s.name, model != null && !s.name.endsWith(String.valueOf(model)) ? " " + model : "", tokens,
                    s.durationMs(), status, customer != null ? " customer=" + customer : "");
        }
        return true;
    }
}
