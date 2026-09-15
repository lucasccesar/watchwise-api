package com.watchwise.watchwise_api.pickstemplate.mapper;

import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateCategoryCreationDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateCategoryDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateCategoryPatchDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateCreationDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplatePatchDTO;
import com.watchwise.watchwise_api.pickstemplate.entity.PickOrigin;
import com.watchwise.watchwise_api.pickstemplate.entity.PickAllowedType;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryGroup;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryOptionMode;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateCategory;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PicksTemplateMapperTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final PicksTemplateMapper mapper = Mappers.getMapper(PicksTemplateMapper.class);

    @Test
    void shouldRequireAtLeastOneTemplateCategory() {
        PicksTemplateCreationDTO request = new PicksTemplateCreationDTO("Awards", null, null, null, null, null, List.of());

        assertThat(validator.validate(request)).extracting(violation -> violation.getPropertyPath().toString())
                .contains("categories");
    }

    @Test
    void shouldRejectOnlyOneEligibilityDateAndInvertedDatePair() {
        PicksTemplateCreationDTO missingEnd = new PicksTemplateCreationDTO("Awards", null, null, null,
                LocalDate.of(2026, 1, 1), null, List.of(category()));
        PicksTemplatePatchDTO inverted = new PicksTemplatePatchDTO(null, null, null, null,
                LocalDate.of(2026, 12, 31), LocalDate.of(2026, 1, 1), false);

        assertThat(validator.validate(missingEnd)).isNotEmpty();
        assertThat(validator.validate(inverted)).isNotEmpty();
    }

    @Test
    void shouldAcceptACompleteEligibilityDatePair() {
        PicksTemplateCreationDTO request = new PicksTemplateCreationDTO("Awards", null, null, null,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), List.of(category()));

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void shouldRequireExplicitIntentToClearAnEligibilityPeriod() {
        PicksTemplatePatchDTO clear = new PicksTemplatePatchDTO(null, null, null, null, null, null, true);
        PicksTemplatePatchDTO conflicting = new PicksTemplatePatchDTO(null, null, null, null,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), true);

        assertThat(clear.hasValidEligibilityDatePair()).isTrue();
        assertThat(conflicting.hasValidEligibilityDatePair()).isFalse();
    }

    @Test
    void shouldExposeOptionModeWithoutApplyEligibilityPeriodInEveryCategoryContract() {
        assertThat(List.of(PicksTemplateCategoryCreationDTO.class, PicksTemplateCategoryPatchDTO.class,
                PicksTemplateCategoryDTO.class))
                .allSatisfy(categoryContract -> assertThat(categoryContract.getRecordComponents())
                        .extracting(RecordComponent::getName)
                        .contains("optionMode")
                        .doesNotContain("applyEligibilityPeriod"));
    }

    @Test
    void shouldMapTemplateCreationFieldsWhileLeavingCreatorDerivedFieldsForTheService() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 12, 31);
        PicksTemplateCreationDTO request = new PicksTemplateCreationDTO("Awards", "Annual picks",
                "https://example.com/cover.png", "Choose one", start, end, List.of(category()));

        PicksTemplate result = mapper.picksTemplateCreationDtoToPicksTemplate(request);

        assertThat(result.getName()).isEqualTo("Awards");
        assertThat(result.getDescription()).isEqualTo("Annual picks");
        assertThat(result.getCoverImage()).isEqualTo("https://example.com/cover.png");
        assertThat(result.getInstructions()).isEqualTo("Choose one");
        assertThat(result.getEligibilityStartDate()).isEqualTo(start);
        assertThat(result.getEligibilityEndDate()).isEqualTo(end);
        assertThat(result.getId()).isNull();
        assertThat(result.getCreator()).isNull();
        assertThat(result.getOrigin()).isNull();
    }

    @Test
    void shouldMapCategoryOptionModeWithoutPersistingServiceAssignedFields() {
        PicksTemplateCategory result = mapper.categoryCreationDtoToPicksTemplateCategory(category());

        assertThat(result.getName()).isEqualTo("Best film");
        assertThat(result.getGroup()).isEqualTo(PickCategoryGroup.PRIMARY);
        assertThat(result.getDisplayOrder()).isEqualTo(1);
        assertThat(result.getAllowedType()).isEqualTo(PickAllowedType.MOVIE);
        assertThat(result.getOptionMode()).isEqualTo(PickCategoryOptionMode.OPEN);
        assertThat(result.getId()).isNull();
        assertThat(result.getPicksTemplate()).isNull();
    }

    @Test
    void shouldMapTemplatePreviewFields() {
        PicksTemplate template = PicksTemplate.builder()
                .id(java.util.UUID.randomUUID())
                .origin(PickOrigin.COMMUNITY)
                .name("Awards")
                .description("Annual picks")
                .coverImage("https://example.com/cover.png")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        var result = mapper.picksTemplateToPreviewDto(template);

        assertThat(result.id()).isEqualTo(template.getId());
        assertThat(result.origin()).isEqualTo(PickOrigin.COMMUNITY);
        assertThat(result.name()).isEqualTo("Awards");
        assertThat(result.description()).isEqualTo("Annual picks");
        assertThat(result.coverImage()).isEqualTo("https://example.com/cover.png");
    }

    private PicksTemplateCategoryCreationDTO category() {
        return new PicksTemplateCategoryCreationDTO("Best film", null, PickCategoryGroup.PRIMARY, 1,
                PickAllowedType.MOVIE, PickCategoryOptionMode.OPEN, List.of());
    }
}
