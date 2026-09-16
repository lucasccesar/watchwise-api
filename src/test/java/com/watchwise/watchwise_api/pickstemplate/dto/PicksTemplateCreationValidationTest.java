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

    private PicksTemplateCategoryCreationDTO categoryWithNullOption() {
        return new PicksTemplateCategoryCreationDTO("Best", null, PickCategoryGroup.PRIMARY, 1,
                PickAllowedType.PERSON, PickCategoryOptionMode.FIXED, Collections.singletonList(null));
    }
}
