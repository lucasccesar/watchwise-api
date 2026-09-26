package com.watchwise.watchwise_api.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class TmdbPosterUrlValidator implements ConstraintValidator<TmdbPosterUrl, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || TmdbPosterUrlPolicy.isValid(value);
    }
}
