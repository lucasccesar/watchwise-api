package com.watchwise.watchwise_api.content.dto;

import com.watchwise.watchwise_api.content.entity.ContentType;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContentRefCreationDTOValidationTest {

    @Test
    void acceptsReleaseYearsAtBothSupportedBoundaries() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();

            assertThat(validator.validate(requestWithReleaseYear(1))).isEmpty();
            assertThat(validator.validate(requestWithReleaseYear(9_999))).isEmpty();
        }
    }

    @Test
    void rejectsReleaseYearAboveSupportedMaximum() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(requestWithReleaseYear(10_000)))
                    .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString())
                            .isEqualTo("releaseYear"));
        }
    }

    private ContentRefCreationDTO requestWithReleaseYear(int year) {
        return new ContentRefCreationDTO("123", ContentType.MOVIE, null, null, null,
                null, null, null, null, year, null);
    }
}
