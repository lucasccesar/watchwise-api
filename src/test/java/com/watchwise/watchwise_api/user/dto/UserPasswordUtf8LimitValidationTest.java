package com.watchwise.watchwise_api.user.dto;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserPasswordUtf8LimitValidationTest {

    @Test
    void rejectsRegistrationPasswordAbove72Utf8Bytes() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new PostUserDTO("watchwise", "Watchwise", "watchwise@example.com",
                    "Aa1" + "é".repeat(35), null, null, true, null);

            assertThat(factory.getValidator().validate(request))
                    .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString())
                            .isEqualTo("password"));
        }
    }

    @Test
    void acceptsRegistrationPasswordAt72Utf8Bytes() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new PostUserDTO("watchwise", "Watchwise", "watchwise@example.com",
                    "Aa1" + "é".repeat(34) + "a", null, null, true, null);

            assertThat(factory.getValidator().validate(request))
                    .noneSatisfy(violation -> assertThat(violation.getPropertyPath().toString())
                            .isEqualTo("password"));
        }
    }

    @Test
    void rejectsCurrentPasswordAbove72Utf8Bytes() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new PatchUserDTO(null, null, null, null, null, null,
                    "é".repeat(37));

            assertThat(factory.getValidator().validate(request))
                    .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString())
                            .isEqualTo("currentPassword"));
        }
    }

    @Test
    void acceptsCurrentPasswordAt72Utf8Bytes() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new PatchUserDTO(null, null, null, null, null, null,
                    "é".repeat(36));

            assertThat(factory.getValidator().validate(request))
                    .noneSatisfy(violation -> assertThat(violation.getPropertyPath().toString())
                            .isEqualTo("currentPassword"));
        }
    }
}
