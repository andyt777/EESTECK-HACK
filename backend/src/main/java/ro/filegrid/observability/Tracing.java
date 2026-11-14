package ro.filegrid.observability;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.Map;
import java.util.UUID;

/**
 * Manual spans of Agent 01 (classify, route, encrypt, storage.put,
 * storage.verify) on top of the automatic ones of the OpenTelemetry Java agent.
 * Without the agent the API is a no-op: the code runs the same, untraced.
 */
public final class Tracing {

    private static final String SCOPE = "ro.filegrid";

    private Tracing() {
    }

    /** Work that may throw a checked exception. */
    @FunctionalInterface
    public interface Work<T, E extends Exception> {
        T run() throws E;
    }

    /** Runs {@code work} in a child span of the current one; failures are recorded on the span. */
    public static <T, E extends Exception> T inSpan(String name, Map<String, String> attributes, Work<T, E> work)
            throws E {
        Tracer tracer = GlobalOpenTelemetry.getTracer(SCOPE);
        Span span = tracer.spanBuilder(name).startSpan();
        attributes.forEach((key, value) -> {
            if (value != null) {
                span.setAttribute(key, value);
            }
        });
        try (Scope ignored = span.makeCurrent()) {
            return work.run();
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, String.valueOf(e.getMessage()));
            throw e;
        } finally {
            span.end();
        }
    }

    /** A started span made current; {@link #close()} ends it. For code with several checked exceptions. */
    public static final class SpanScope implements AutoCloseable {
        private final Span span;
        private final Scope scope;

        private SpanScope(Span span) {
            this.span = span;
            this.scope = span.makeCurrent();
        }

        @Override
        public void close() {
            scope.close();
            span.end();
        }
    }

    /** Starts a child span of the current one; use with try-with-resources. */
    public static SpanScope start(String name, Map<String, String> attributes) {
        Span span = GlobalOpenTelemetry.getTracer(SCOPE).spanBuilder(name).startSpan();
        attributes.forEach((key, value) -> {
            if (value != null) {
                span.setAttribute(key, value);
            }
        });
        return new SpanScope(span);
    }

    /** Trace id of the current span, or {@code null} when nothing is traced. */
    public static String currentTraceId() {
        SpanContext context = Span.current().getSpanContext();
        return context.isValid() ? context.getTraceId() : null;
    }

    /** The current trace id (so the File Passport links to Tempo), else a random id of the same shape. */
    public static String currentTraceIdOrRandom() {
        String traceId = currentTraceId();
        return traceId != null ? traceId : UUID.randomUUID().toString().replace("-", "");
    }
}
