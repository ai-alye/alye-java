package ai.alye;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** Map provider exceptions to the same short error types the Python and Node.js SDKs use. */
final class ErrorTypes {
    private ErrorTypes() {}

    private static final Map<String, String> BY_NAME = new HashMap<>();

    static {
        // openai-java and anthropic-java
        BY_NAME.put("RateLimitException", "rate_limited");
        BY_NAME.put("UnauthorizedException", "auth");
        BY_NAME.put("PermissionDeniedException", "auth");
        BY_NAME.put("BadRequestException", "invalid_request");
        BY_NAME.put("UnprocessableEntityException", "invalid_request");
        BY_NAME.put("NotFoundException", "not_found");
        BY_NAME.put("InternalServerException", "server_error");
        BY_NAME.put("OverloadedException", "overloaded");
        // LangChain4j (RateLimitException and InternalServerException share the names above)
        BY_NAME.put("AuthenticationException", "auth");
        BY_NAME.put("InvalidRequestException", "invalid_request");
        BY_NAME.put("ModelNotFoundException", "not_found");
        BY_NAME.put("ContentFilteredException", "content_filter");
        BY_NAME.put("TimeoutException", "timeout");
    }

    static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        while ((cur instanceof CompletionException || cur instanceof ExecutionException) && cur.getCause() != null) cur = cur.getCause();
        return cur;
    }

    static String classify(Throwable error) {
        Throwable t = unwrap(error);
        String byName = BY_NAME.get(t.getClass().getSimpleName());
        if (byName != null) return byName;
        Integer status = statusCode(t);
        if (status != null) {
            if (status == 429) return "rate_limited";
            if (status == 401 || status == 403) return "auth";
            if (status == 408 || status == 504) return "timeout";
            if (status == 503 || status == 529) return "overloaded";
            if (status >= 500) return "server_error";
            if (status >= 400) return "invalid_request";
        }
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof HttpTimeoutException || c instanceof SocketTimeoutException || c instanceof TimeoutException
                    || c instanceof InterruptedIOException) return "timeout";
            if (c instanceof ConnectException || c instanceof UnknownHostException) return "connection_error";
            if (c instanceof CancellationException) return "cancelled";
            if (c.getCause() == c) break;
        }
        String n = t.getClass().getSimpleName();
        if (n.endsWith("IoException") || n.endsWith("IOException")) return "connection_error";
        return n.isEmpty() ? "error" : n;
    }

    /** The HTTP status of a provider error, read from statusCode() when the exception has one. */
    static Integer statusCode(Throwable error) {
        Throwable t = unwrap(error);
        for (String m : new String[] {"statusCode", "httpStatusCode", "getStatusCode"}) {
            try {
                Object v = t.getClass().getMethod(m).invoke(t);
                if (v instanceof Number) {
                    int code = ((Number) v).intValue();
                    if (code >= 100 && code < 1000) return code;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // not a provider error
            }
        }
        return null;
    }
}
