package com.watchwise.watchwise_api.platformimport;

import com.watchwise.watchwise_api.WatchwiseApiApplication;
import com.watchwise.watchwise_api.DemoDataSeedApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableAutoConfiguration
@EntityScan(basePackageClasses = WatchwiseApiApplication.class)
@EnableJpaRepositories(basePackageClasses = WatchwiseApiApplication.class)
@ComponentScan(
        basePackages = "com.watchwise.watchwise_api",
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                        classes = {
                                WatchwiseApiApplication.class,
                                DemoDataSeedApplication.class,
                                PlatformImportTestApplication.class
                        }),
                @ComponentScan.Filter(type = FilterType.REGEX,
                        pattern = "com\\.watchwise\\.watchwise_api\\..*Test\\$.*"),
                @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = TestConfiguration.class)
        })
public class PlatformImportTestApplication { }
