package com.watchwise.watchwise_api.user.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PostUserDTOValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldRejectRegistrationWhenNameIsMissing() {
        PostUserDTO dto = new PostUserDTO(
                "username", null, "username@email.com", "Password123", null, null, null, null);

        assertThat(validator.validate(dto))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("name");
    }

    @Test
    void acceptsEmailShorterThanElevenCharacters() {
        PostUserDTO dto = new PostUserDTO(
                "username", "Name", "a@b.co", "Password123", null, null, true, null);

        assertThat(validator.validate(dto))
                .noneSatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("email"));
    }

    @Test
    void rejectsEmailLongerThanSixtyCharacters() {
        String email = "a".repeat(56) + "@b.co";
        PostUserDTO dto = new PostUserDTO(
                "username", "Name", email, "Password123", null, null, true, null);

        assertThat(validator.validate(dto))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("email"));
    }
}
