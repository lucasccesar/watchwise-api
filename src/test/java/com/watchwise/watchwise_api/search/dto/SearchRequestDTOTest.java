package com.watchwise.watchwise_api.search.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchRequestDTOTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldAcceptQueryAtMaximumLength() {
        SearchRequestDTO request = new SearchRequestDTO("a".repeat(100), null, null, null);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void shouldRejectQueryAboveMaximumLength() {
        SearchRequestDTO request = new SearchRequestDTO("a".repeat(101), null, null, null);

        assertThat(validator.validateProperty(request, "q"))
                .extracting(violation -> violation.getMessage())
                .containsExactly("q must contain at most 100 characters");
    }
}
