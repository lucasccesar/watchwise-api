package com.watchwise.watchwise_api.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class JsonRequestBodyLimitFilterTest {

    @Test
    void reportsConfiguredLimitAndKnownReceivedSizeForOversizedJson() throws Exception {
        long max = 2L * 1024 * 1024;
        byte[] body = new byte[3 * 1024 * 1024];
        MockHttpServletRequest request = request(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new JsonRequestBodyLimitFilter(new ObjectMapper(), max).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("2 MiB", "3 MiB", "Payload Too Large");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void identifiesOversizedContentLengthAsDeclaredWhenRejectingWithoutReadingTheBody() throws Exception {
        long max = 2L * 1024 * 1024;
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/register") {
            @Override
            public long getContentLengthLong() {
                return 3L * 1024 * 1024;
            }
        };
        request.setContentType("application/json");
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        new JsonRequestBodyLimitFilter(new ObjectMapper(), max).doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("2 MiB", "Content-Length declares 3 MiB")
                .doesNotContain("received 3 MiB");
    }

    @Test
    void passesBoundedJsonThroughAReplayableRequestBody() throws Exception {
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = request(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new JsonRequestBodyLimitFilter(new ObjectMapper(), 8).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest().getInputStream().readAllBytes()).containsExactly(body);
    }

    private MockHttpServletRequest request(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/register");
        request.setContentType("application/json");
        request.setContent(body);
        return request;
    }
}
