package com.watchwise.watchwise_api.common.tmdb;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class TmdbImageTransportWiringTest {

    @Test
    void productionTransportUsesImageClientWithoutAuthorizationOrRedirects() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("tmdb-test", Map.of(
                    "app.tmdb.base-url", "https://api.themoviedb.org/3",
                    "app.tmdb.api-key", "private-test-key",
                    "app.tmdb.timeout-ms", "1000")));
            context.register(TmdbClientConfig.class, TmdbImageTransport.class);
            context.refresh();

            TmdbImageTransport transport = context.getBean(TmdbImageTransport.class);
            RestClient imageClient = context.getBean("tmdbImageRestClient", RestClient.class);
            assertThat(ReflectionTestUtils.getField(transport, "client")).isSameAs(imageClient);

            RestClient.Builder builder = imageClient.mutate();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("https://image.tmdb.org/t/p/w500/poster.jpg"))
                    .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                    .andRespond(withStatus(HttpStatus.OK));
            builder.build().get().uri("https://image.tmdb.org/t/p/w500/poster.jpg")
                    .exchange((request, response) -> response.getStatusCode());
            server.verify();

            Object factory = ReflectionTestUtils.getField(imageClient, "clientRequestFactory");
            assertThat(factory).isInstanceOf(SimpleClientHttpRequestFactory.class);
            HttpURLConnection connection = new HttpURLConnection(new URL("https://image.tmdb.org")) {
                @Override public void disconnect() {}
                @Override public boolean usingProxy() { return false; }
                @Override public void connect() {}
            };
            Method prepare = factory.getClass().getDeclaredMethod(
                    "prepareConnection", HttpURLConnection.class, String.class);
            prepare.setAccessible(true);
            prepare.invoke(factory, connection, "GET");
            assertThat(connection.getInstanceFollowRedirects()).isFalse();
        }
    }
}
