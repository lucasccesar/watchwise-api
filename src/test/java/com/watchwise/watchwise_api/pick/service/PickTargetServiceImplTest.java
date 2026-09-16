package com.watchwise.watchwise_api.pick.service;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.pick.dto.PickContentTargetDTO;
import com.watchwise.watchwise_api.pick.dto.PickTargetDTO;
import com.watchwise.watchwise_api.pick.entity.PickSelection;
import com.watchwise.watchwise_api.pickstemplate.entity.PickAllowedType;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryOptionMode;
import com.watchwise.watchwise_api.pickstemplate.entity.PickOrigin;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateCategory;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateOption;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateOptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PickTargetServiceImplTest {

    @Mock private ContentService contentService;
    @Mock private ContentRepository contentRepository;
    @Mock private PicksTemplateOptionRepository optionRepository;
    @Mock private TmdbClient tmdbClient;

    private PickTargetService service;

    @BeforeEach
    void setUp() {
        service = new PickTargetServiceImpl(contentService, contentRepository, optionRepository, tmdbClient);
    }

    @Test
    void shouldResolveOpenMovieAndPassOnlyItsIdentityToContentService() {
        PicksTemplate template = template(null, null);
        PicksTemplateCategory category = category(template, PickAllowedType.MOVIE, PickCategoryOptionMode.OPEN);
        Content content = content("550", ContentType.MOVIE, null, null, null);
        stubContentResolution(content);
        when(tmdbClient.getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(found(movie("1999-03-31")));

        ResolvedPickTarget result = service.validateForCategory(UUID.randomUUID(), template, category,
                contentTarget(PickAllowedType.MOVIE, "550", null, null, null));

        assertThat(result.content()).isSameAs(content);
        assertThat(result.personTmdbId()).isNull();
        assertThat(result.key().content()).isEqualTo(new ResolvedPickTarget.ContentKey(ContentType.MOVIE, "550", null, null, null));
        ArgumentCaptor<ContentRefCreationDTO> captor = ArgumentCaptor.forClass(ContentRefCreationDTO.class);
        verify(contentService).getOrCreateReference(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new ContentRefCreationDTO("550", ContentType.MOVIE,
                null, null, null, null, null, null, null, null, null));
    }

    @Test
    void shouldRejectContentWithTheWrongIdentityShapeForItsCategory() {
        PicksTemplate template = template(null, null);
        PicksTemplateCategory category = category(template, PickAllowedType.EPISODE, PickCategoryOptionMode.OPEN);

        assertThatThrownBy(() -> service.validateForCategory(UUID.randomUUID(), template, category,
                contentTarget(PickAllowedType.EPISODE, "episode-id", "1399", 1, 2)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.validateForCategory(UUID.randomUUID(), template, category,
                contentTarget(PickAllowedType.PERSON, null, "1399", 1, null)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.validateForCategory(UUID.randomUUID(), template, category,
                contentTarget(PickAllowedType.EPISODE, null, "1399", -1, 2)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.validateForCategory(UUID.randomUUID(), template, category,
                contentTarget(PickAllowedType.EPISODE, null, "1399", 0, 0)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void shouldResolveOpenPersonWithOptionalContextAndIgnoreTheContextDate() {
        PicksTemplate template = template(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31));
        PicksTemplateCategory category = category(template, PickAllowedType.PERSON, PickCategoryOptionMode.OPEN);
        Content context = content("550", ContentType.MOVIE, null, null, null);
        stubContentResolution(context);
        when(tmdbClient.getPersonDetails("42")).thenReturn(found(new TmdbPersonDetails("42")));
        when(tmdbClient.getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(found(movie("1999-03-31")));

        ResolvedPickTarget result = service.validateForCategory(UUID.randomUUID(), template, category,
                new PickTargetDTO(null, "42", new PickContentTargetDTO(PickAllowedType.MOVIE, "550", null, null, null)));

        assertThat(result.personTmdbId()).isEqualTo("42");
        assertThat(result.contextContent()).isSameAs(context);
        assertThat(result.key().personTmdbId()).isEqualTo("42");
    }

    @Test
    void shouldRejectContentOnlyAndMalformedPersonTargets() {
        PicksTemplate template = template(null, null);
        PicksTemplateCategory category = category(template, PickAllowedType.PERSON, PickCategoryOptionMode.OPEN);

        assertThatThrownBy(() -> service.validateForCategory(UUID.randomUUID(), template, category,
                contentTarget(PickAllowedType.MOVIE, "550", null, null, null)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.validateForCategory(UUID.randomUUID(), template, category,
                new PickTargetDTO(null, "person-42", null)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void shouldValidateFixedPersonWithoutResolvingContentAgain() {
        PicksTemplate template = template(null, null);
        PicksTemplateCategory category = category(template, PickAllowedType.PERSON, PickCategoryOptionMode.FIXED);
        PicksTemplateOption option = PicksTemplateOption.builder().category(category).personTmdbId(" 42 ").build();
        when(optionRepository.findByCategoryId(category.getId())).thenReturn(List.of(option));
        when(tmdbClient.getPersonDetails("42")).thenReturn(found(new TmdbPersonDetails("42")));

        ResolvedPickTarget result = service.validateForCategory(UUID.randomUUID(), template, category,
                new PickTargetDTO(null, "42", null));

        assertThat(result.personTmdbId()).isEqualTo("42");
        assertThat(service.matches(option, result)).isTrue();
        verify(tmdbClient).getPersonDetails("42");
        verifyNoInteractions(contentService, contentRepository);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("[validateForCategory] Should Reject Fixed Movie - When Period Changed Before First Use")
    void shouldRejectFixedMovieWhenPeriodChangedBeforeFirstUse() {
        PicksTemplate template = template(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        PicksTemplateCategory category = category(template, PickAllowedType.MOVIE, PickCategoryOptionMode.FIXED);
        when(optionRepository.findByCategoryId(category.getId())).thenReturn(List.of(
                PicksTemplateOption.builder().category(category)
                        .content(content("550", ContentType.MOVIE, null, null, null)).build()));
        when(tmdbClient.getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(found(movie("2025-06-01")));

        assertThatThrownBy(() -> service.validateForCategory(UUID.randomUUID(), template, category,
                contentTarget(PickAllowedType.MOVIE, "550", null, null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Content is outside the template eligibility period");
        verifyNoInteractions(contentService, contentRepository);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("[validateForCategory] Should Reject Fixed Target - When Removed Or Unavailable")
    void shouldRejectFixedTargetWhenRemovedOrUnavailable() {
        PicksTemplate template = template(null, null);
        PicksTemplateCategory category = category(template, PickAllowedType.MOVIE, PickCategoryOptionMode.FIXED);
        when(optionRepository.findByCategoryId(category.getId())).thenReturn(List.of(
                PicksTemplateOption.builder().category(category)
                        .content(content("550", ContentType.MOVIE, null, null, null)).build()));
        when(tmdbClient.getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.NotFound<>(), new TmdbLookupResult.Unavailable<>());

        assertThatThrownBy(() -> service.validateForCategory(UUID.randomUUID(), template, category,
                contentTarget(PickAllowedType.MOVIE, "550", null, null, null)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.validateForCategory(UUID.randomUUID(), template, category,
                contentTarget(PickAllowedType.MOVIE, "550", null, null, null)))
                .isInstanceOf(TmdbUnavailableException.class);
        verifyNoInteractions(contentService, contentRepository);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("[validateForCategory] Should Ignore Dates - When Fixed Person Has Context")
    void shouldIgnoreDatesWhenFixedPersonHasContext() {
        PicksTemplate template = template(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        PicksTemplateCategory category = category(template, PickAllowedType.PERSON, PickCategoryOptionMode.FIXED);
        Content context = content("550", ContentType.MOVIE, null, null, null);
        when(optionRepository.findByCategoryId(category.getId())).thenReturn(List.of(
                PicksTemplateOption.builder().category(category).personTmdbId("42").contextContent(context).build()));
        when(tmdbClient.getPersonDetails("42")).thenReturn(found(new TmdbPersonDetails("42")));
        when(tmdbClient.getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(found(movie("1999-03-31")));

        ResolvedPickTarget result = service.validateForCategory(UUID.randomUUID(), template, category,
                new PickTargetDTO(null, "42", new PickContentTargetDTO(PickAllowedType.MOVIE, "550", null, null, null)));

        assertThat(result.contextContent()).isSameAs(context);
        verifyNoInteractions(contentService, contentRepository);
    }

    @Test
    void shouldRejectFixedSelectionWhenNoCompatibleOptionExists() {
        PicksTemplate template = template(null, null);
        PicksTemplateCategory category = category(template, PickAllowedType.MOVIE, PickCategoryOptionMode.FIXED);
        when(optionRepository.findByCategoryId(category.getId())).thenReturn(List.of());

        assertThatThrownBy(() -> service.validateForCategory(UUID.randomUUID(), template, category,
                contentTarget(PickAllowedType.MOVIE, "550", null, null, null)))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(tmdbClient, contentService, contentRepository);
    }

    @Test
    void shouldRejectFixedOptionValidationForOpenCategory() {
        PicksTemplate template = template(null, null);
        PicksTemplateCategory category = category(template, PickAllowedType.MOVIE, PickCategoryOptionMode.OPEN);

        assertThatThrownBy(() -> service.validateFixedOption(UUID.randomUUID(), template, category,
                contentTarget(PickAllowedType.MOVIE, "550", null, null, null)))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(tmdbClient, contentService, contentRepository, optionRepository);
    }

    @Test
    void shouldAcceptInclusiveGlobalPeriodBoundariesAndRejectMissingContentDate() {
        PicksTemplate template = template(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));
        PicksTemplateCategory category = category(template, PickAllowedType.MOVIE, PickCategoryOptionMode.OPEN);
        Content content = content("550", ContentType.MOVIE, null, null, null);
        stubContentResolution(content);
        when(tmdbClient.getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(found(movie("2024-01-01")), found(movie("2024-12-31")), found(movie(null)));

        service.validateForCategory(UUID.randomUUID(), template, category, contentTarget(PickAllowedType.MOVIE, "550", null, null, null));
        service.validateForCategory(UUID.randomUUID(), template, category, contentTarget(PickAllowedType.MOVIE, "550", null, null, null));
        assertThatThrownBy(() -> service.validateForCategory(UUID.randomUUID(), template, category,
                contentTarget(PickAllowedType.MOVIE, "550", null, null, null)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void shouldAllowMissingDateWhenTheTemplateHasNoCompletePeriod() {
        PicksTemplate template = template(LocalDate.of(2024, 1, 1), null);
        PicksTemplateCategory category = category(template, PickAllowedType.SERIES, PickCategoryOptionMode.OPEN);
        Content content = content("1399", ContentType.SERIES, null, null, null);
        stubContentResolution(content);
        when(tmdbClient.getTvFullDetails("1399", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(found(tv(null)));

        ResolvedPickTarget result = service.validateForCategory(UUID.randomUUID(), template, category,
                contentTarget(PickAllowedType.SERIES, "1399", null, null, null));

        assertThat(result.content()).isSameAs(content);
    }

    @Test
    void shouldUseEpisodeCompositeIdentityAndAirDateForEligibility() {
        PicksTemplate template = template(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));
        PicksTemplateCategory category = category(template, PickAllowedType.EPISODE, PickCategoryOptionMode.OPEN);
        Content content = content(null, ContentType.EPISODE, "1399", 0, 1);
        stubContentResolution(content);
        when(tmdbClient.getEpisodeFullDetails("1399", 0, 1, TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(found(episode("2024-06-01")));

        ResolvedPickTarget result = service.validateForCategory(UUID.randomUUID(), template, category,
                contentTarget(PickAllowedType.EPISODE, null, "1399", 0, 1));

        assertThat(result.content()).isSameAs(content);
        verify(tmdbClient).getEpisodeFullDetails("1399", 0, 1, TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE);
    }

    @Test
    void shouldMapTmdbNotFoundAndUnavailableToTheirDomainExceptions() {
        PicksTemplate template = template(null, null);
        PicksTemplateCategory category = category(template, PickAllowedType.MOVIE, PickCategoryOptionMode.OPEN);
        when(tmdbClient.getMovieFullDetails(eq("404"), any())).thenReturn(new TmdbLookupResult.NotFound<>());
        when(tmdbClient.getMovieFullDetails(eq("503"), any())).thenReturn(new TmdbLookupResult.Unavailable<>());

        assertThatThrownBy(() -> service.validateForCategory(UUID.randomUUID(), template, category,
                contentTarget(PickAllowedType.MOVIE, "404", null, null, null)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.validateForCategory(UUID.randomUUID(), template, category,
                contentTarget(PickAllowedType.MOVIE, "503", null, null, null)))
                .isInstanceOf(TmdbUnavailableException.class);
        verify(contentService, never()).getOrCreateReference(any());
    }

    @Test
    void shouldTreatPersistedFixedSelectionAsValidOnlyWhileItStillMatchesAnOption() {
        PicksTemplate template = template(null, null);
        PicksTemplateCategory category = category(template, PickAllowedType.MOVIE, PickCategoryOptionMode.FIXED);
        Content content = content("550", ContentType.MOVIE, null, null, null);
        PicksTemplateOption option = PicksTemplateOption.builder().category(category).content(content).build();
        PickSelection selection = PickSelection.builder().category(category).content(content).build();
        when(optionRepository.findByCategoryId(category.getId())).thenReturn(List.of(option));
        when(tmdbClient.getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(found(movie("1999-03-31")));

        assertThat(service.isValid(UUID.randomUUID(), template, category, selection)).isTrue();

        when(optionRepository.findByCategoryId(category.getId())).thenReturn(List.of());
        assertThat(service.isValid(UUID.randomUUID(), template, category, selection)).isFalse();
    }

    @Test
    void shouldValidatePersistedOpenMovieAgainstCurrentTmdbDate() {
        PicksTemplate template = template(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));
        PicksTemplateCategory category = category(template, PickAllowedType.MOVIE, PickCategoryOptionMode.OPEN);
        Content content = content("550", ContentType.MOVIE, null, null, null);
        when(tmdbClient.getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(found(movie("2024-06-01")));

        assertThat(service.isValid(UUID.randomUUID(), template, category, selection(category, content))).isTrue();
        verify(tmdbClient).getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE);
    }

    @Test
    void shouldInvalidatePersistedOpenContentOutsideTheCurrentPeriod() {
        PicksTemplate template = template(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));
        PicksTemplateCategory category = category(template, PickAllowedType.SERIES, PickCategoryOptionMode.OPEN);
        Content content = content("1399", ContentType.SERIES, null, null, null);
        when(tmdbClient.getTvFullDetails("1399", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(found(tv("2023-12-31")));

        assertThat(service.isValid(UUID.randomUUID(), template, category, selection(category, content))).isFalse();
        verify(tmdbClient).getTvFullDetails("1399", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE);
    }

    @Test
    void shouldInvalidatePersistedOpenContentWhenTmdbDateIsMissing() {
        PicksTemplate template = template(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));
        PicksTemplateCategory category = category(template, PickAllowedType.MOVIE, PickCategoryOptionMode.OPEN);
        Content content = content("550", ContentType.MOVIE, null, null, null);
        when(tmdbClient.getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(found(movie(null)));

        assertThat(service.isValid(UUID.randomUUID(), template, category, selection(category, content))).isFalse();
    }

    @Test
    void shouldInvalidatePersistedOpenContentWhenTmdbCannotFindIt() {
        PicksTemplate template = template(null, null);
        PicksTemplateCategory category = category(template, PickAllowedType.MOVIE, PickCategoryOptionMode.OPEN);
        Content content = content("404", ContentType.MOVIE, null, null, null);
        when(tmdbClient.getMovieFullDetails("404", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.NotFound<>());

        assertThat(service.isValid(UUID.randomUUID(), template, category, selection(category, content))).isFalse();
    }

    @Test
    void shouldAcceptInclusivePeriodBoundariesForPersistedContent() {
        PicksTemplate template = template(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));
        PicksTemplateCategory category = category(template, PickAllowedType.MOVIE, PickCategoryOptionMode.OPEN);
        Content content = content("550", ContentType.MOVIE, null, null, null);
        when(tmdbClient.getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(found(movie("2024-01-01")), found(movie("2024-12-31")));

        assertThat(service.isValid(UUID.randomUUID(), template, category, selection(category, content))).isTrue();
        assertThat(service.isValid(UUID.randomUUID(), template, category, selection(category, content))).isTrue();
    }

    @Test
    void shouldAllowMissingPersistedContentDateWhenTheTemplateHasNoCompletePeriod() {
        PicksTemplate template = template(LocalDate.of(2024, 1, 1), null);
        PicksTemplateCategory category = category(template, PickAllowedType.MOVIE, PickCategoryOptionMode.OPEN);
        Content content = content("550", ContentType.MOVIE, null, null, null);
        when(tmdbClient.getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(found(movie(null)));

        assertThat(service.isValid(UUID.randomUUID(), template, category, selection(category, content))).isTrue();
    }

    @Test
    void shouldPropagateTmdbUnavailableWhenValidatingPersistedOpenContent() {
        PicksTemplate template = template(null, null);
        PicksTemplateCategory category = category(template, PickAllowedType.EPISODE, PickCategoryOptionMode.OPEN);
        Content content = content(null, ContentType.EPISODE, "1399", 1, 2);
        when(tmdbClient.getEpisodeFullDetails("1399", 1, 2, TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.Unavailable<>());

        assertThatThrownBy(() -> service.isValid(UUID.randomUUID(), template, category, selection(category, content)))
                .isInstanceOf(TmdbUnavailableException.class);
    }

    @Test
    void shouldInvalidatePersistedPersonWhenTmdbCannotFindIt() {
        PicksTemplate template = template(null, null);
        PicksTemplateCategory category = category(template, PickAllowedType.PERSON, PickCategoryOptionMode.OPEN);
        when(tmdbClient.getPersonDetails("42")).thenReturn(new TmdbLookupResult.NotFound<>());

        PickSelection selection = PickSelection.builder().category(category).personTmdbId("42").build();

        assertThat(service.isValid(UUID.randomUUID(), template, category, selection)).isFalse();
    }

    @Test
    void shouldPropagateTmdbUnavailableWhenValidatingPersistedPerson() {
        PicksTemplate template = template(null, null);
        PicksTemplateCategory category = category(template, PickAllowedType.PERSON, PickCategoryOptionMode.OPEN);
        when(tmdbClient.getPersonDetails("42")).thenReturn(new TmdbLookupResult.Unavailable<>());

        PickSelection selection = PickSelection.builder().category(category).personTmdbId("42").build();

        assertThatThrownBy(() -> service.isValid(UUID.randomUUID(), template, category, selection))
                .isInstanceOf(TmdbUnavailableException.class);
    }

    @Test
    void shouldInvalidatePersistedPersonWhenItsContextCannotBeFound() {
        PicksTemplate template = template(null, null);
        PicksTemplateCategory category = category(template, PickAllowedType.PERSON, PickCategoryOptionMode.OPEN);
        Content context = content("404", ContentType.MOVIE, null, null, null);
        when(tmdbClient.getPersonDetails("42")).thenReturn(found(new TmdbPersonDetails("42")));
        when(tmdbClient.getMovieFullDetails("404", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.NotFound<>());

        PickSelection selection = PickSelection.builder().category(category).personTmdbId("42")
                .contextContent(context).build();

        assertThat(service.isValid(UUID.randomUUID(), template, category, selection)).isFalse();
    }

    @Test
    void shouldPropagateTmdbUnavailableWhenValidatingPersistedPersonContext() {
        PicksTemplate template = template(null, null);
        PicksTemplateCategory category = category(template, PickAllowedType.PERSON, PickCategoryOptionMode.OPEN);
        Content context = content("503", ContentType.MOVIE, null, null, null);
        when(tmdbClient.getPersonDetails("42")).thenReturn(found(new TmdbPersonDetails("42")));
        when(tmdbClient.getMovieFullDetails("503", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.Unavailable<>());

        PickSelection selection = PickSelection.builder().category(category).personTmdbId("42")
                .contextContent(context).build();

        assertThatThrownBy(() -> service.isValid(UUID.randomUUID(), template, category, selection))
                .isInstanceOf(TmdbUnavailableException.class);
    }

    @Test
    void shouldInvalidatePersistedSelectionWithMissingTmdbIdentity() {
        PicksTemplate template = template(null, null);
        PicksTemplateCategory category = category(template, PickAllowedType.MOVIE, PickCategoryOptionMode.OPEN);
        Content content = content(null, ContentType.MOVIE, null, null, null);

        assertThat(service.isValid(UUID.randomUUID(), template, category, selection(category, content))).isFalse();
        verifyNoInteractions(tmdbClient);
    }

    @Test
    void shouldRecheckTmdbPeriodForFixedContentAfterTheTemplatePeriodChanges() {
        PicksTemplate template = template(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));
        PicksTemplateCategory category = category(template, PickAllowedType.MOVIE, PickCategoryOptionMode.FIXED);
        Content content = content("550", ContentType.MOVIE, null, null, null);
        PicksTemplateOption option = PicksTemplateOption.builder().category(category).content(content).build();
        when(optionRepository.findByCategoryId(category.getId())).thenReturn(List.of(option));
        when(tmdbClient.getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(found(movie("2023-06-01")));

        assertThat(service.isValid(UUID.randomUUID(), template, category, selection(category, content))).isFalse();
        verify(tmdbClient).getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE);
    }

    @Test
    void shouldRejectPersistedFixedSelectionBeforeTmdbWhenOptionWasRemoved() {
        PicksTemplate template = template(null, null);
        PicksTemplateCategory category = category(template, PickAllowedType.MOVIE, PickCategoryOptionMode.FIXED);
        Content content = content("550", ContentType.MOVIE, null, null, null);
        when(optionRepository.findByCategoryId(category.getId())).thenReturn(List.of());

        assertThat(service.isValid(UUID.randomUUID(), template, category, selection(category, content))).isFalse();
        verifyNoInteractions(tmdbClient);
    }

    @Test
    void shouldValidateOpenPersonAndContextButIgnoreContextDateEligibility() {
        PicksTemplate template = template(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));
        PicksTemplateCategory category = category(template, PickAllowedType.PERSON, PickCategoryOptionMode.OPEN);
        Content context = content("550", ContentType.MOVIE, null, null, null);
        when(tmdbClient.getPersonDetails("42")).thenReturn(found(new TmdbPersonDetails("42")));
        when(tmdbClient.getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(found(movie("1999-03-31")));

        PickSelection selection = PickSelection.builder().category(category).personTmdbId("42").contextContent(context).build();
        assertThat(service.isValid(UUID.randomUUID(), template, category, selection)).isTrue();
        verify(tmdbClient).getPersonDetails("42");
        verify(tmdbClient).getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE);
    }

    @Test
    void shouldValidateFixedPersonMembershipAndIgnoreContextDateEligibility() {
        PicksTemplate template = template(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));
        PicksTemplateCategory category = category(template, PickAllowedType.PERSON, PickCategoryOptionMode.FIXED);
        Content context = content("550", ContentType.MOVIE, null, null, null);
        PicksTemplateOption option = PicksTemplateOption.builder().category(category).personTmdbId("42")
                .contextContent(context).build();
        when(optionRepository.findByCategoryId(category.getId())).thenReturn(List.of(option));
        when(tmdbClient.getPersonDetails("42")).thenReturn(found(new TmdbPersonDetails("42")));
        when(tmdbClient.getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(found(movie("1999-03-31")));

        PickSelection selection = PickSelection.builder().category(category).personTmdbId("42").contextContent(context).build();
        assertThat(service.isValid(UUID.randomUUID(), template, category, selection)).isTrue();
        verify(tmdbClient).getPersonDetails("42");
        verify(tmdbClient).getMovieFullDetails("550", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE);
    }

    @Test
    void shouldInvalidatePersistedPersonWhenContextIsNotAContentTarget() {
        PicksTemplate template = template(null, null);
        PicksTemplateCategory category = category(template, PickAllowedType.PERSON, PickCategoryOptionMode.OPEN);
        Content invalidContext = content(null, ContentType.SEASON, "1399", 1, null);
        PickSelection selection = PickSelection.builder().category(category).personTmdbId("42")
                .contextContent(invalidContext).build();

        assertThat(service.isValid(UUID.randomUUID(), template, category, selection)).isFalse();
        verifyNoInteractions(tmdbClient, contentService);
    }

    private PickTargetDTO contentTarget(PickAllowedType type, String tmdbId, String seriesTmdbId,
                                        Integer seasonNumber, Integer episodeNumber) {
        return new PickTargetDTO(new PickContentTargetDTO(type, tmdbId, seriesTmdbId, seasonNumber, episodeNumber), null, null);
    }

    private PickSelection selection(PicksTemplateCategory category, Content content) {
        return PickSelection.builder().category(category).content(content).build();
    }

    private PicksTemplate template(LocalDate start, LocalDate end) {
        return PicksTemplate.builder().id(UUID.randomUUID()).origin(PickOrigin.OFFICIAL).name("Template")
                .eligibilityStartDate(start).eligibilityEndDate(end).build();
    }

    private PicksTemplateCategory category(PicksTemplate template, PickAllowedType type, PickCategoryOptionMode mode) {
        return PicksTemplateCategory.builder().id(UUID.randomUUID()).picksTemplate(template).name("Category")
                .allowedType(type).optionMode(mode).build();
    }

    private Content content(String tmdbId, ContentType type, String seriesTmdbId, Integer seasonNumber, Integer episodeNumber) {
        return Content.builder().id(UUID.randomUUID()).tmdbId(tmdbId).type(type).seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber).episodeNumber(episodeNumber).build();
    }

    private void stubContentResolution(Content content) {
        LocalDateTime now = LocalDateTime.now();
        ContentRefDTO reference = new ContentRefDTO(content.getId(), content.getTmdbId(), content.getType(),
                content.getSeriesTmdbId(), content.getSeasonNumber(), content.getEpisodeNumber(), null, null, now, now);
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class))).thenReturn(reference);
        when(contentRepository.getReferenceById(content.getId())).thenReturn(content);
    }

    private TmdbMovieFullDetails movie(String date) {
        return new TmdbMovieFullDetails("550", "Movie", null, null, null, null, date,
                null, null, null, null, null, null, null, null, null, null);
    }

    private TmdbTvFullDetails tv(String date) {
        return new TmdbTvFullDetails("1399", "Series", null, null, null, null, date, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private TmdbEpisodeFullDetails episode(String date) {
        return new TmdbEpisodeFullDetails(1, "Episode", null, date, 1, 0, null, null, null);
    }

    private <T> TmdbLookupResult<T> found(T value) {
        return new TmdbLookupResult.Found<>(value);
    }
}
