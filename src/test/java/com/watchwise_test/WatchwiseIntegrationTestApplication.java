package com.watchwise_test;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@AutoConfigurationPackage(basePackages = "com.watchwise.watchwise_api")
@EnableJpaRepositories(basePackages = "com.watchwise.watchwise_api")
@ComponentScan(
        basePackages = "com.watchwise.watchwise_api",
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*\\$.*"),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                        com.watchwise.watchwise_api.DemoDataSeedApplication.class,
                        com.watchwise.watchwise_api.WatchwiseApiApplication.class,
                        com.watchwise.watchwise_api.platformimport.PlatformImportTestApplication.class
                }),
                @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = TestConfiguration.class)
        })
public class WatchwiseIntegrationTestApplication {
}
