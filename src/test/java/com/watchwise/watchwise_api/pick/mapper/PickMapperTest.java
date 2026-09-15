package com.watchwise.watchwise_api.pick.mapper;

import com.watchwise.watchwise_api.pick.dto.PickContentTargetDTO;
import com.watchwise.watchwise_api.pick.dto.PickCreationDTO;
import com.watchwise.watchwise_api.pick.dto.PickSelectionCreationDTO;
import com.watchwise.watchwise_api.pick.dto.PickTargetDTO;
import com.watchwise.watchwise_api.pick.entity.PickSelection;
import com.watchwise.watchwise_api.pickstemplate.entity.PickAllowedType;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PickMapperTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private PickMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = Mappers.getMapper(PickMapper.class);
        ReflectionTestUtils.setField(mapper, "contentMapper", Mappers.getMapper(ContentMapper.class));
    }

    @Test
    void shouldRejectEmptySelectionsBecauseAPickRequiresAtLeastOneSelection() {
        PickCreationDTO request = new PickCreationDTO(null, List.of());

        assertThat(validator.validate(request)).extracting(violation -> violation.getPropertyPath().toString())
                .contains("selections");
    }

    @Test
    void shouldAcceptOneValidSelection() {
        PickCreationDTO request = new PickCreationDTO(null, List.of(selection(UUID.randomUUID())));

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void shouldLeaveDuplicateCategoryIdsForTheServiceToReject() {
        UUID categoryId = UUID.randomUUID();
        PickCreationDTO request = new PickCreationDTO(null, List.of(selection(categoryId), selection(categoryId)));

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void shouldRejectNegativeSeasonNumberAndZeroEpisodeNumber() {
        PickContentTargetDTO target = new PickContentTargetDTO(PickAllowedType.EPISODE, null, "1399", -1, 0);
        PickCreationDTO request = new PickCreationDTO(null, List.of(new PickSelectionCreationDTO(UUID.randomUUID(),
                new PickTargetDTO(target, null, null))));

        assertThat(validator.validate(request)).extracting(violation -> violation.getPropertyPath().toString())
                .contains("selections[0].target.content.seasonNumber", "selections[0].target.content.episodeNumber");
    }

    @Test
    void shouldRejectNonNumericPersonTmdbId() {
        PickCreationDTO request = new PickCreationDTO(null, List.of(new PickSelectionCreationDTO(UUID.randomUUID(),
                new PickTargetDTO(null, "person-42", null))));

        assertThat(validator.validate(request)).extracting(violation -> violation.getPropertyPath().toString())
                .contains("selections[0].target.personTmdbId");
    }

    @Test
    void shouldMapAllPersistedTargetReferencesToSearchOption() {
        Content content = content("550", ContentType.MOVIE);
        Content contextContent = content("1399", ContentType.SERIES);
        PickSelection selection = PickSelection.builder()
                .id(UUID.randomUUID())
                .content(content)
                .personTmdbId("123")
                .contextContent(contextContent)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        var result = mapper.pickSelectionToSearchDto(selection);

        assertThat(result.id()).isEqualTo(selection.getId());
        assertThat(result.content().id()).isEqualTo(content.getId());
        assertThat(result.personTmdbId()).isEqualTo("123");
        assertThat(result.contextContent().id()).isEqualTo(contextContent.getId());
    }

    private PickSelectionCreationDTO selection(UUID categoryId) {
        return new PickSelectionCreationDTO(categoryId, new PickTargetDTO(
                new PickContentTargetDTO(PickAllowedType.MOVIE, "550", null, null, null), null, null));
    }

    private Content content(String tmdbId, ContentType type) {
        LocalDateTime now = LocalDateTime.now();
        return Content.builder()
                .id(UUID.randomUUID())
                .tmdbId(tmdbId)
                .type(type)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
