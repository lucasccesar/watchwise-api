package com.watchwise.watchwise_api.common.config;

import com.zaxxer.hikari.HikariDataSource;
import com.watchwise.watchwise_api.platformimport.config.ImportWorkerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionConfigurationTest {
    @Test
    void bindsWorkerDelayAndRejectsNonPositiveValues() {
        workerRunner("5000").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ImportWorkerProperties.class).fixedDelayMs()).isEqualTo(5000);
        });

        workerRunner("0").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("fixed-delay-ms");
        });
    }

    @Test
    void rejectsSimultaneouslyActiveDevAndProdProfiles() {
        new ApplicationContextRunner()
                .withUserConfiguration(ProductionDataSourcePolicy.class)
                .withPropertyValues("spring.profiles.active=dev,prod")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootMessage(context.getStartupFailure())).contains("dev", "prod");
                });
    }

    @Test
    void resolvesNativeHerokuDatasourceVariablesWithoutChangingPlusInPassword() {
        String url = "jdbc:postgresql://localhost:5432/watchwise?sslmode=require";
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("heroku", Map.of(
                "SPRING_DATASOURCE_URL", url,
                "SPRING_DATASOURCE_USERNAME", "watchwise-user",
                "SPRING_DATASOURCE_PASSWORD", "student+credit")));

        assertThat(environment.getProperty("spring.datasource.url")).isEqualTo(url);
        assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("watchwise-user");
        assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("student+credit");
    }

    @Test
    void bindsNativeDatasourceVariablesIntoSpringBootDatasourceProperties() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("heroku", Map.of(
                "SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/watchwise?sslmode=require",
                "SPRING_DATASOURCE_USERNAME", "watchwise-user",
                "SPRING_DATASOURCE_PASSWORD", "student+credit")));
        new ApplicationContextRunner()
                .withUserConfiguration(NativeDataSourceConfiguration.class)
                .withInitializer(context -> context.setEnvironment(environment))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    DataSourceProperties dataSource = context.getBean(DataSourceProperties.class);
                    assertThat(dataSource.getUrl())
                            .isEqualTo("jdbc:postgresql://localhost:5432/watchwise?sslmode=require");
                    assertThat(dataSource.getUsername()).isEqualTo("watchwise-user");
                    assertThat(dataSource.getPassword()).isEqualTo("student+credit");
                });
    }

    @Test
    void validatesTheConfiguredHikariBeanBeforeTheContextCanUseIt() {
        new ApplicationContextRunner()
                .withUserConfiguration(ProductionDataSourcePolicy.class, TestDataSourceConfiguration.class)
                .withPropertyValues("spring.profiles.active=prod")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootMessage(context.getStartupFailure()))
                            .contains("sslmode")
                            .doesNotContain("private-host", "hidden-password");
                });
    }

    @Test
    void allowsAValidProductionHikariDatasourceBeforeAnyConnectionIsOpened() {
        new ApplicationContextRunner()
                .withUserConfiguration(ProductionDataSourcePolicy.class, ValidTestDataSourceConfiguration.class)
                .withPropertyValues("spring.profiles.active=prod")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(HikariDataSource.class).getMaximumPoolSize()).isEqualTo(8);
                });
    }

    @Test
    void resolvesProductionSecretsFromEnvironmentAndKeepsThemOutOfPlaceholderErrors() {
        try (var context = productionSecretsContext(Map.of(
                "JWT_SECRET", "jwt+secret",
                "CORS_ALLOWED_ORIGINS", "https://app.watch-wise.app",
                "APP_TMDB_API_KEY", "tmdb+secret",
                "RESEND_API_KEY", "resend+secret",
                "RESEND_FROM_EMAIL", "noreply@watch-wise.app"))) {
            assertThat(context.getBean(ProductionSecrets.class)).isEqualTo(new ProductionSecrets(
                    "jwt+secret", "https://app.watch-wise.app", "tmdb+secret", "resend+secret",
                    "noreply@watch-wise.app"));
        }

        assertThatThrownBy(() -> productionSecretsContext(Map.of(
                "CORS_ALLOWED_ORIGINS", "https://app.watch-wise.app",
                "APP_TMDB_API_KEY", "sensitive-test-value",
                "RESEND_API_KEY", "test-resend-key",
                "RESEND_FROM_EMAIL", "noreply@watch-wise.app")))
                .satisfies(failure -> {
                    String message = rootMessage(failure);
                    assertThat(message).contains("JWT_SECRET");
                    assertThat(message).doesNotContain("sensitive-test-value", "test-resend-key");
                });
    }

    private AnnotationConfigApplicationContext productionSecretsContext(Map<String, Object> environmentValues) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.getPropertySources().addFirst(new MapPropertySource("production-test-secrets", environmentValues));
        context.setEnvironment(environment);
        context.register(ProductionSecretsConfiguration.class);
        try {
            context.refresh();
            return context;
        } catch (RuntimeException failure) {
            context.close();
            throw failure;
        }
    }

    private ApplicationContextRunner workerRunner(String fixedDelayMs) {
        return new ApplicationContextRunner()
                .withUserConfiguration(WorkerPropertiesConfiguration.class)
                .withPropertyValues("app.import.worker.fixed-delay-ms=" + fixedDelayMs);
    }

    private String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ImportWorkerProperties.class)
    static class WorkerPropertiesConfiguration { }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DataSourceProperties.class)
    static class NativeDataSourceConfiguration { }

    @Configuration(proxyBeanMethods = false)
    @PropertySource("classpath:application-prod.properties")
    static class ProductionSecretsConfiguration {
        @Bean
        static PropertySourcesPlaceholderConfigurer placeholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        ProductionSecrets productionSecrets(@Value("${app.jwt.secret}") String jwtSecret,
                                            @Value("${app.cors.allowed-origins}") String cors,
                                            @Value("${app.tmdb.api-key}") String tmdbApiKey,
                                            @Value("${app.email.resend.api-key}") String resendApiKey,
                                            @Value("${app.email.resend.from}") String resendFrom) {
            return new ProductionSecrets(jwtSecret, cors, tmdbApiKey, resendApiKey, resendFrom);
        }
    }

    private record ProductionSecrets(String jwtSecret, String cors, String tmdbApiKey,
                                     String resendApiKey, String resendFrom) { }

    @Configuration(proxyBeanMethods = false)
    static class TestDataSourceConfiguration {
        @Bean(destroyMethod = "close")
        HikariDataSource dataSource() {
            HikariDataSource source = new HikariDataSource();
            source.setJdbcUrl("jdbc:postgresql://private-host:5432/watchwise?sslmode=disable&reWriteBatchedInserts=true");
            source.setUsername("watchwise-user");
            source.setPassword("hidden-password");
            source.setDriverClassName("org.postgresql.Driver");
            source.setMaximumPoolSize(8);
            source.setMinimumIdle(2);
            source.setConnectionTimeout(5000);
            source.addDataSourceProperty("sslmode", "require");
            source.addDataSourceProperty("reWriteBatchedInserts", "true");
            return source;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ValidTestDataSourceConfiguration {
        @Bean(destroyMethod = "close")
        HikariDataSource dataSource() {
            HikariDataSource source = new HikariDataSource();
            source.setJdbcUrl("jdbc:postgresql://localhost:5432/watchwise?sslmode=verify-full&reWriteBatchedInserts=true");
            source.setUsername("watchwise-user");
            source.setPassword("student+credit");
            source.setDriverClassName("org.postgresql.Driver");
            source.setMaximumPoolSize(8);
            source.setMinimumIdle(2);
            source.setConnectionTimeout(5000);
            source.addDataSourceProperty("sslmode", "require");
            source.addDataSourceProperty("reWriteBatchedInserts", "true");
            return source;
        }
    }
}
