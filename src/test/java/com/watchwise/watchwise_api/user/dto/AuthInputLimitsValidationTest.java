package com.watchwise.watchwise_api.user.dto;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthInputLimitsValidationTest {

    @Test
    void rejectsLoginIdentifierOutsideOneToSixtyCharacters() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new LoginUserDTO("", "Password123")))
                    .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("identifier"));
            assertThat(validator.validate(new LoginUserDTO("u".repeat(61), "Password123")))
                    .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("identifier"));
            assertThat(validator.validate(new LoginUserDTO("u".repeat(60), "Password123")))
                    .noneSatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("identifier"));
        }
    }

    @Test
    void boundsGoogleAndEmailConfirmationTokens() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new OAuthLoginDTO("t".repeat(16_385))))
                    .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("token"));
            assertThat(validator.validate(new OAuthLoginDTO("t".repeat(16_384))))
                    .noneSatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("token"));
            assertThat(validator.validate(new ConfirmEmailDTO("t".repeat(129))))
                    .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("token"));
            assertThat(validator.validate(new ConfirmEmailDTO("t".repeat(128))))
                    .noneSatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("token"));
        }
    }
}
