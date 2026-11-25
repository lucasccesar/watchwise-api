package com.watchwise.watchwise_api.common.performance;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenchmarkRequestFilterTest {
    private final AtomicLong clock = new AtomicLong();
    private final List<BenchmarkRequestFilter.Measurement> events = new ArrayList<>();
    private final BenchmarkRequestFilter filter = new BenchmarkRequestFilter(clock::get, events::add);

    @Test
    void registersOnlyWhenExplicitlyEnabled() {
        var runner = new ApplicationContextRunner().withUserConfiguration(BenchmarkObservationConfiguration.class);
        runner.run(context -> assertThat(context).doesNotHaveBean(FilterRegistrationBean.class));
        runner.withPropertyValues("app.benchmark.observation.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(FilterRegistrationBean.class));
    }

    @Test
    void scopesSqlTimingAndCorrelationToTheSelectedRequest() throws Exception {
        var request = request("GET", "/users/13682472-8326-e673-b665-ffd90b7eb8db/following");
        request.setQueryString("secret=do-not-record");
        request.addHeader("Cookie", "private-cookie");
        request.addHeader(BenchmarkRequestFilter.CORRELATION_HEADER, "untrusted");
        var response = new MockHttpServletResponse();
        var jdbc = new BenchmarkRequestFilter.SqlEvents();

        filter.doFilter(request, response, (req, res) -> {
            jdbc.jdbcExecuteStatementStart();
            clock.addAndGet(30);
            jdbc.jdbcExecuteStatementEnd();
            CompletableFuture.runAsync(() -> {
                var unrelated = new BenchmarkRequestFilter.SqlEvents();
                unrelated.jdbcExecuteStatementStart();
                unrelated.jdbcExecuteStatementEnd();
            }).join();
            jdbc.jdbcExecuteBatchStart();
            clock.addAndGet(40);
            jdbc.jdbcExecuteBatchEnd();
            clock.addAndGet(30);
            response.setStatus(206);
            response.getWriter().write("unchanged response");
        });

        var sample = events.getFirst();
        assertThat(sample.route()).isEqualTo("/users/{userId}/following");
        assertThat(sample.status()).isEqualTo(206);
        assertThat(sample.durationNanos()).isEqualTo(100);
        assertThat(sample.sqlStatements()).isEqualTo(1);
        assertThat(sample.sqlBatches()).isEqualTo(1);
        assertThat(sample.sqlNanos()).isEqualTo(70);
        assertThat(sample.maxSqlNanos()).isEqualTo(40);
        assertThat(sample.toString()).doesNotContain("do-not-record", "private-cookie", "13682472", "untrusted");
        assertThat(response.getHeader(BenchmarkRequestFilter.CORRELATION_HEADER)).isEqualTo(sample.correlationId());
        assertThat(response.getContentAsString()).isEqualTo("unchanged response");

        var next = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/feed"), next, (req, res) -> next.getWriter().write("feed"));
        assertThat(events.getLast().sqlStatements()).isZero();
        assertThat(events.getLast().correlationId()).isNotEqualTo(sample.correlationId());
    }

    @Test
    void ignoresWriteMethodsAndUnselectedRoutes() throws Exception {
        for (var req : List.of(request("POST", "/picks-templates"), request("GET", "/watchlist"))) {
            var response = new MockHttpServletResponse();
            filter.doFilter(req, response, (servletRequest, servletResponse) -> response.getWriter().write("unchanged"));
            assertThat(response.getHeader(BenchmarkRequestFilter.CORRELATION_HEADER)).isNull();
            assertThat(response.getContentAsString()).isEqualTo("unchanged");
        }
        assertThat(events).isEmpty();
    }

    @Test
    void preservesExceptionAndDoesNotInventFinalStatus() throws Exception {
        var failure = new ServletException("secret detail");
        assertThatThrownBy(() -> filter.doFilter(request("GET", "/notifications/view"), new MockHttpServletResponse(),
                (req, res) -> { throw failure; })).isSameAs(failure);
        assertThat(events.getFirst().status()).isNull();
        assertThat(events.getFirst().outcome()).isEqualTo("exception");
        assertThat(events.getFirst().toString()).doesNotContain("secret detail");
        filter.doFilter(request("GET", "/feed"), new MockHttpServletResponse(), (req, res) -> {});
        assertThat(events.getLast().sqlStatements()).isZero();
    }

    private static MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, "/api/v1" + path);
        request.setContextPath("/api/v1");
        return request;
    }
}
