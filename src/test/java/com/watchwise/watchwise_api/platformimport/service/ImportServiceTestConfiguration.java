package com.watchwise.watchwise_api.platformimport.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties(ImportLimits.class)
class ImportServiceTestConfiguration {
    @Bean
    Clock clock() {
        return Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC);
    }
}
