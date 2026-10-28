package com.watchwise.watchwise_api.common.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class ProductionDataSourcePolicy {
    private static final Set<String> REQUIRED_TLS_MODES = Set.of("require", "verify-ca", "verify-full");

    @Bean
    static BeanPostProcessor productionDataSourcePolicyPostProcessor(Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("dev"))
                && environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("Spring profiles dev and prod cannot be active together");
        }
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof HikariDataSource dataSource) validate(dataSource);
                return bean;
            }
        };
    }

    public static void validate(HikariConfig config) {
        if (config == null) throw new IllegalStateException("Production datasource configuration is missing");
        if (config.getJdbcUrl() == null || config.getJdbcUrl().isBlank()) {
            throw new IllegalStateException("Production datasource URL is required");
        }
        if (config.getMaximumPoolSize() < 1 || config.getMinimumIdle() < 0
                || config.getMinimumIdle() > config.getMaximumPoolSize()
                || config.getConnectionTimeout() <= 0) {
            throw new IllegalStateException("Production Hikari pool limits are invalid");
        }

        final DriverPropertyInfo[] properties;
        try {
            properties = DriverManager.getDriver(config.getJdbcUrl())
                    .getPropertyInfo(config.getJdbcUrl(), config.getDataSourceProperties());
        } catch (SQLException | RuntimeException exception) {
            throw new IllegalStateException("Cannot validate production datasource properties");
        }
        String sslmode = effectiveValue(properties, "sslmode");
        if (sslmode == null || !REQUIRED_TLS_MODES.contains(sslmode.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("Production datasource must use sslmode require, verify-ca, or verify-full");
        }
        if (!"true".equalsIgnoreCase(effectiveValue(properties, "reWriteBatchedInserts"))) {
            throw new IllegalStateException("Production datasource must enable reWriteBatchedInserts");
        }
    }

    private static String effectiveValue(DriverPropertyInfo[] properties, String name) {
        if (properties == null) return null;
        for (DriverPropertyInfo property : properties) {
            if (property != null && name.equalsIgnoreCase(property.name)) return property.value;
        }
        return null;
    }
}
