package com.watchwise.watchwise_api.contentreview.dto;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContentReviewCreationDTOValidationTest {

    @Test
    void acceptsReviewAtTenThousandCharactersAndRejectsLongerText() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new ContentReviewCreationDTO("r".repeat(10_000), 8, 0L)))
                    .noneSatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("comment"));
            assertThat(validator.validate(new ContentReviewCreationDTO("r".repeat(10_001), 8, 0L)))
                    .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("comment"));
        }
    }
}
