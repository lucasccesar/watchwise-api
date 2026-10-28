package com.watchwise.watchwise_api.user.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PatchUserDTOValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rejectsEmptyEmailWhenSupplied() {
        PatchUserDTO dto = new PatchUserDTO(null, "", null, null, null, null, null);

        assertThat(validator.validate(dto))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("email"));
    }

    @Test
    void rejectsWhitespaceOnlyEmailWhenSupplied() {
        PatchUserDTO dto = new PatchUserDTO(null, "   ", null, null, null, null, null);

        assertThat(validator.validate(dto))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("email"));
    }

    @Test
    void allowsOmittingEmailFromPatch() {
        PatchUserDTO dto = new PatchUserDTO(null, null, null, null, null, null, null);

        assertThat(validator.validate(dto))
                .noneSatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("email"));
    }
}
