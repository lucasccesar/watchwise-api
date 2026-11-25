package com.watchwise.watchwise_api.common.performance;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.hibernate.SessionEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

final class BenchmarkRequestFilter extends OncePerRequestFilter {

    static final String CORRELATION_HEADER = "X-WatchWise-Benchmark-Request";
    private static final Logger METRICS = LoggerFactory.getLogger("watchwise.benchmark.request");
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();
    private static final Map<String, String> OPERATIONS = Map.of(
            "/feed", "feed",
            "/picks-templates", "picks-catalog",
            "/users/me/picks/view", "my-picks-view",
            "/users/me/picks-templates/view", "my-templates-view",
            "/notifications/view", "notifications-view",
            "/users/me", "session-user",
            "/users/{userId}/summary/home", "home-summary",
            "/users/{userId}/followers", "followers",
            "/users/{userId}/following", "following"
    );
    private static final Pattern USER_ROUTE = Pattern.compile(
            "^/users/[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}/(followers|following|summary/home)$");

    private final LongSupplier clock;
    private final Consumer<Measurement> sink;

    BenchmarkRequestFilter() {
        this(System::nanoTime, BenchmarkRequestFilter::log);
    }

    BenchmarkRequestFilter(LongSupplier clock, Consumer<Measurement> sink) {
        this.clock = clock;
        this.sink = sink;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"GET".equals(request.getMethod()) || !OPERATIONS.containsKey(normalizedPath(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var id = UUID.randomUUID().toString();
        var route = normalizedPath(request);
        var scope = new Scope(clock);
        var previous = ACTIVE.get();
        ACTIVE.set(scope);
        boolean returned = false;
        try {
            response.setHeader(CORRELATION_HEADER, id);
            chain.doFilter(request, response);
            returned = true;
        } finally {
            long elapsed = clock.getAsLong() - scope.started;
            if (previous == null) {
                ACTIVE.remove();
            } else {
                ACTIVE.set(previous);
            }
            boolean async = request.isAsyncStarted();
            String outcome = !returned ? "exception" : async ? "async-handoff" : "complete";
            Integer status = returned && !async ? response.getStatus() : null;
            var measurement = new Measurement(id, OPERATIONS.get(route), "GET", route, status, elapsed,
                    scope.statements, scope.batches, scope.sqlNanos, scope.maxSqlNanos, scope.pending, outcome);
            try {
                sink.accept(measurement);
            } catch (RuntimeException ignored) {
                METRICS.warn("benchmark_observation_emit_failed");
            }
        }
    }

    private static String normalizedPath(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        var userRoute = USER_ROUTE.matcher(path);
        return userRoute.matches() ? "/users/{userId}/" + userRoute.group(1) : path;
    }

    private static void log(Measurement m) {
        METRICS.atInfo()
                .addKeyValue("correlation_id", m.correlationId)
                .addKeyValue("operation", m.operation)
                .addKeyValue("method", m.method)
                .addKeyValue("route", m.route)
                .addKeyValue("status", m.status)
                .addKeyValue("api_ns", m.durationNanos)
                .addKeyValue("hibernate_statements", m.sqlStatements)
                .addKeyValue("hibernate_batches", m.sqlBatches)
                .addKeyValue("hibernate_execute_ns", m.sqlNanos)
                .addKeyValue("hibernate_execute_max_ns", m.maxSqlNanos)
                .addKeyValue("incomplete_sql_events", m.incompleteSqlEvents)
                .addKeyValue("outcome", m.outcome)
                .addKeyValue("sql_coverage", "hibernate-request-thread")
                .log("benchmark_observation");
    }

    record Measurement(String correlationId, String operation, String method, String route, Integer status,
                       long durationNanos, int sqlStatements, int sqlBatches, long sqlNanos, long maxSqlNanos,
                       int incompleteSqlEvents, String outcome) {
    }

    private static final class Scope {
        private final LongSupplier clock;
        private final long started;
        private int statements;
        private int batches;
        private int pending;
        private long sqlNanos;
        private long maxSqlNanos;

        private Scope(LongSupplier clock) {
            this.clock = clock;
            this.started = clock.getAsLong();
        }

        void complete(long time) {
            pending--;
            sqlNanos += time;
            maxSqlNanos = Math.max(maxSqlNanos, time);
        }
    }

    public static final class SqlEvents implements SessionEventListener {
        private Execution statement;
        private Execution batch;

        @Override
        public void jdbcExecuteStatementStart() {
            Scope scope = ACTIVE.get();
            if (scope != null) {
                scope.statements++;
            }
            statement = start(scope);
        }

        @Override
        public void jdbcExecuteStatementEnd() {
            finish(statement);
            statement = null;
        }

        @Override
        public void jdbcExecuteBatchStart() {
            Scope scope = ACTIVE.get();
            if (scope != null) {
                scope.batches++;
            }
            batch = start(scope);
        }

        @Override
        public void jdbcExecuteBatchEnd() {
            finish(batch);
            batch = null;
        }

        private static Execution start(Scope scope) {
            if (scope == null) {
                return null;
            }
            scope.pending++;
            return new Execution(scope, scope.clock.getAsLong());
        }

        private static void finish(Execution execution) {
            if (execution != null && execution.scope == ACTIVE.get()) {
                var scope = execution.scope;
                scope.complete(scope.clock.getAsLong() - execution.started);
            }
        }

        private record Execution(Scope scope, long started) {
        }
    }
}
