package com.watchwise.watchwise_api.common.tmdb;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class TmdbClientConfig {

    @Bean
    public RestClient tmdbRestClient(
            @Value("${app.tmdb.base-url}") String baseUrl,
            @Value("${app.tmdb.api-key}") String apiKey,
            @Value("${app.tmdb.timeout-ms}") long timeoutMs) {
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect()
                .build(HttpClientSettings.defaults()
                        .withConnectTimeout(Duration.ofMillis(timeoutMs))
                        .withReadTimeout(Duration.ofMillis(timeoutMs)));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }
}
