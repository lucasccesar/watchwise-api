package com.watchwise.watchwise_api.dailygame.config;

import com.watchwise.watchwise_api.dailygame.service.DailyGameAttemptDetailsCleanupService;
import com.watchwise.watchwise_api.dailygame.service.DailyChallengeGenerationService;
import com.watchwise.watchwise_api.dailygame.generation.DailyGameGenerationJob;
import com.watchwise.watchwise_api.dailygame.tracking.DailyGameAttemptDetailsCleanupJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DailyGameProductionConfigurationTest {

    @Test
    @DisplayName("[production properties] Should Resolve Daily Game Schedules - When The Production Context Starts")
    void shouldResolveDailyGameSchedulesWhenTheProductionContextStarts() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                ProductionSchedulingConfiguration.class)) {
            assertThat(context.getBean(DailyGameAttemptDetailsCleanupJob.class)).isNotNull();
            assertThat(context.getBean(DailyGameGenerationJob.class)).isNotNull();
            assertThat(context.getEnvironment().getProperty("app.daily-games.generation.cron"))
                    .isEqualTo("0 */10 * * * *");
            assertThat(context.getEnvironment().getProperty("app.daily-games.attempt-details-cleanup.cron"))
                    .isEqualTo("0 5 0 * * *");
        }
    }

    @Configuration
    @EnableScheduling
    @PropertySource("classpath:application-prod.properties")
    static class ProductionSchedulingConfiguration {

        @Bean
        static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        DailyGameAttemptDetailsCleanupService cleanupService() {
            return mock(DailyGameAttemptDetailsCleanupService.class);
        }

        @Bean
        DailyChallengeGenerationService generationService() {
            return mock(DailyChallengeGenerationService.class);
        }

        @Bean
        Clock clock() {
            return Clock.system(ZoneOffset.UTC);
        }

        @Bean
        DailyGameAttemptDetailsCleanupJob cleanupJob(
                DailyGameAttemptDetailsCleanupService cleanupService, Clock clock) {
            return new DailyGameAttemptDetailsCleanupJob(cleanupService, clock);
        }

        @Bean
        DailyGameGenerationJob generationJob(DailyChallengeGenerationService generationService, Clock clock) {
            return new DailyGameGenerationJob(generationService, clock);
        }
    }
}
