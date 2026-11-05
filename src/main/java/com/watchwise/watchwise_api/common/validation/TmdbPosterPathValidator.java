package com.watchwise.watchwise_api.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class TmdbPosterPathValidator implements ConstraintValidator<TmdbPosterPath, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || TmdbPosterPathPolicy.isValid(value);
    }
}
