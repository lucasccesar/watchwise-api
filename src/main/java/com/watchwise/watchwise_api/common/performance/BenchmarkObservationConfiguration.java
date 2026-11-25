package com.watchwise.watchwise_api.common.performance;

import jakarta.servlet.DispatcherType;
import org.hibernate.cfg.SessionEventSettings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.benchmark.observation.enabled", havingValue = "true")
class BenchmarkObservationConfiguration {

    @Bean
    FilterRegistrationBean<BenchmarkRequestFilter> benchmarkRequestFilter() {
        var registration = new FilterRegistrationBean<>(new BenchmarkRequestFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        return registration;
    }

    @Bean
    HibernatePropertiesCustomizer benchmarkHibernateEvents() {
        return properties -> properties.putIfAbsent(
                SessionEventSettings.AUTO_SESSION_EVENTS_LISTENER, BenchmarkRequestFilter.SqlEvents.class.getName());
    }
}
