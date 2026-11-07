package com.watchwise.watchwise_api;

import com.watchwise.watchwise_api.common.config.SchedulingConfig;
import com.watchwise.watchwise_api.common.config.SecurityConfig;
import com.watchwise.watchwise_api.platformimport.service.impl.LetterboxdSeedLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Locale;

@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
        basePackages = "com.watchwise.watchwise_api",
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                        classes = {WatchwiseApiApplication.class, SecurityConfig.class,
                                SchedulingConfig.class, LetterboxdSeedLoader.class}),
                @ComponentScan.Filter(type = FilterType.ANNOTATION,
                        classes = {Controller.class, RestController.class})
        })
public class DemoDataSeedApplication {

    @Bean
    Clock demoSeedClock() {
        return Clock.systemUTC();
    }

    @Bean
    PasswordEncoder demoSeedPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    public static void main(String[] args) {
        Locale.setDefault(Locale.US);
        SpringApplication application = new SpringApplication(DemoDataSeedApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = application.run(args)) {
            SpringApplication.exit(context);
        }
    }
}
