package com.watchwise.watchwise_api.pickstemplate.dto;

import com.watchwise.watchwise_api.pickstemplate.entity.PickAllowedType;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryGroup;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryOptionMode;
import jakarta.validation.Validation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PicksTemplateCreationValidationTest {

    @Test
    @DisplayName("[createTemplate] Should Reject Null Initial Option - When Nested In Template")
    void shouldRejectNullInitialOptionWhenNestedInTemplate() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new PicksTemplateCreationDTO("Awards", null, null, null, null, null,
                    List.of(categoryWithNullOption()));

            assertThat(factory.getValidator().validate(request))
                    .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString())
                            .isEqualTo("categories[0].options[0].<list element>"));
        }
    }

    @Test
    @DisplayName("[addCategory] Should Reject Null Initial Option - When Nested In Category")
    void shouldRejectNullInitialOptionWhenNestedInCategory() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(categoryWithNullOption()))
                    .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString())
                            .isEqualTo("options[0].<list element>"));
        }
    }

    @Test
    @DisplayName("[createTemplate] Should Reject More Than 50 Categories")
    void shouldRejectMoreThan50Categories() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var categories = java.util.stream.IntStream.rangeClosed(1, 51)
                    .mapToObj(index -> new PicksTemplateCategoryCreationDTO("Category " + index, null,
                            PickCategoryGroup.PRIMARY, index, PickAllowedType.MOVIE,
                            PickCategoryOptionMode.OPEN, List.of()))
                    .toList();

            assertThat(factory.getValidator().validate(template(categories)))
                    .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString())
                            .isEqualTo("categories"));
        }
    }

    @Test
    @DisplayName("[createTemplate] Should Reject More Than 100 Options Per Category")
    void shouldRejectMoreThan100OptionsPerCategory() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var category = categoryWithOptions(101, 0);

            assertThat(factory.getValidator().validate(template(List.of(category))))
                    .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString())
                            .isEqualTo("categories[0].options"));
        }
    }

    @Test
    @DisplayName("[createTemplate] Should Reject More Than 200 Options Per Template")
    void shouldRejectMoreThan200OptionsPerTemplate() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var categories = List.of(categoryWithOptions(67, 0), categoryWithOptions(67, 100),
                    categoryWithOptions(67, 200));

            assertThat(factory.getValidator().validate(template(categories)))
                    .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString())
                            .isEqualTo("atMost200Options"));
        }
    }

    @Test
    @DisplayName("[createTemplate] Should Accept 200 Options And 100 Per Category")
    void shouldAcceptMaximumOptionCounts() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(template(List.of(
                    categoryWithOptions(100, 0), categoryWithOptions(100, 100)))))
                    .isEmpty();
        }
    }

    private PicksTemplateCreationDTO template(List<PicksTemplateCategoryCreationDTO> categories) {
        return new PicksTemplateCreationDTO("Awards", null, null, null, null, null, categories);
    }

    private PicksTemplateCategoryCreationDTO categoryWithOptions(int count, int offset) {
        List<PicksTemplateOptionCreationDTO> options = java.util.stream.IntStream.range(offset, offset + count)
                .mapToObj(index -> new PicksTemplateOptionCreationDTO(
                        new com.watchwise.watchwise_api.pick.dto.PickTargetDTO(null,
                                Integer.toString(index + 1), null)))
                .toList();
        return new PicksTemplateCategoryCreationDTO("Fixed " + offset, null, PickCategoryGroup.PRIMARY,
                offset + 1, PickAllowedType.PERSON, PickCategoryOptionMode.FIXED, options);
    }

    private PicksTemplateCategoryCreationDTO categoryWithNullOption() {
        return new PicksTemplateCategoryCreationDTO("Best", null, PickCategoryGroup.PRIMARY, 1,
                PickAllowedType.PERSON, PickCategoryOptionMode.FIXED, Collections.singletonList(null));
    }
}
