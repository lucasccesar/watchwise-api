package com.watchwise.watchwise_api.user.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class AdminBootstrapEnabledCondition implements Condition {

    private static final String PROPERTY = "app.auth.admin-bootstrap.token";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String token = context.getEnvironment().getProperty(PROPERTY);
        return token != null && token.matches("[A-Za-z0-9_-]{32,}");
    }
}
