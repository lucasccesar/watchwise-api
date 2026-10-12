package com.watchwise.watchwise_api.pickstemplate.service.impl;

import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSearchPage;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvSearchResult;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.pick.dto.PickOptionSearchDTO;
import com.watchwise.watchwise_api.pick.dto.PickOptionViewDTO;
import com.watchwise.watchwise_api.pick.dto.PickPersonPreviewDTO;
import com.watchwise.watchwise_api.pick.dto.PickTargetPreviewDTO;
import com.watchwise.watchwise_api.pick.service.impl.PickTargetPreviewAssembler;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
import com.watchwise.watchwise_api.pick.dto.PickTargetDTO;
import com.watchwise.watchwise_api.pick.repository.PickSelectionRepository;
import com.watchwise.watchwise_api.pick.service.PickTargetService;
import com.watchwise.watchwise_api.pick.service.ResolvedPickTarget;
import com.watchwise.watchwise_api.pickstemplate.entity.*;
import com.watchwise.watchwise_api.pickstemplate.mapper.PicksTemplateOptionMapper;
import com.watchwise.watchwise_api.pickstemplate.repository.*;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.PageImpl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PicksTemplateOptionServiceImplTest {
    @Mock PicksTemplateRepository templateRepository;
    @Mock PicksTemplateCategoryRepository categoryRepository;
    @Mock PicksTemplateOptionRepository optionRepository;
    @Mock PickSelectionRepository selectionRepository;
    @Mock UserRepository userRepository;
    @Mock PickTargetService pickTargetService;
    @Mock PicksTemplateOptionMapper optionMapper;
    @Mock TmdbClient tmdbClient;
    @Mock RequestThrottler requestThrottler;
    @Mock PickTargetPreviewAssembler previewAssembler;
    PicksTemplateOptionServiceImpl service;

    @BeforeEach
    void initializeService() {
        service = new PicksTemplateOptionServiceImpl(templateRepository, categoryRepository, optionRepository,
                selectionRepository, userRepository, pickTargetService, optionMapper, new PageRequestFactory(),
                tmdbClient, requestThrottler, previewAssembler);
    }

    @Test
    @DisplayName("[getOptionViews] Should Enrich Only The Selected Page And Preserve Metadata - When Open Options Are Requested")
    void shouldEnrichOnlyTheSearchPageAndPreservePageMetadataWhenGettingOptionViews() {
        UUID viewerId = UUID.randomUUID();
        PicksTemplateCategory category = category();
        UUID templateId = category.getPicksTemplate().getId();
        UUID categoryId = category.getId();
        PickOptionSearchDTO target = new PickOptionSearchDTO(null, null, "42", null);
        PickTargetPreviewDTO preview = new PickTargetPreviewDTO(null,
                new com.watchwise.watchwise_api.pick.dto.PickPersonPreviewDTO("42", "Ada", null,
                        com.watchwise.watchwise_api.content.dto.ContentPreviewStatus.PARTIAL), null);
        when(categoryRepository.findByIdAndPicksTemplateId(categoryId, templateId)).thenReturn(Optional.of(category));
        when(tmdbClient.searchPeople("Ada", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE, 2))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbSearchPage<>(2,
                        List.of(new TmdbPersonSearchResult("42", "Ada", null)), 5, 81)));
        when(previewAssembler.assemble(eq(List.of(target)), any(ContentCardContext.class)))
                .thenReturn(List.of(preview));

        Page<PickOptionViewDTO> result = service.getOptionViews(viewerId, templateId, categoryId,
                "Ada", null, null, 2, 2);

        assertThat(result.getContent()).containsExactly(new PickOptionViewDTO(null, target, preview));
        assertThat(result.getTotalElements()).isEqualTo(81);
        assertThat(result.getTotalPages()).isEqualTo(5);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.getNumber()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(2);
        verify(previewAssembler).assemble(eq(List.of(target)), any(ContentCardContext.class));
        verify(requestThrottler).checkAllowed(eq("search|" + viewerId), anyInt(), any());
        verifyNoInteractions(optionRepository);
    }

    @Test
    @DisplayName("[getOptionViews] Should Cap Page Size At Twenty - When Client Requests A Larger Visual Page")
    void shouldCapVisualOptionPageSizeAtTwentyWhenClientRequestsLargerPage() {
        UUID viewerId = UUID.randomUUID();
        PicksTemplateCategory category = category();
        UUID templateId = category.getPicksTemplate().getId();
        UUID categoryId = category.getId();
        PickOptionSearchDTO target = new PickOptionSearchDTO(null, null, "42", null);
        PickTargetPreviewDTO preview = new PickTargetPreviewDTO(null,
                new com.watchwise.watchwise_api.pick.dto.PickPersonPreviewDTO("42", "Ada", null,
                        com.watchwise.watchwise_api.content.dto.ContentPreviewStatus.PARTIAL), null);
        when(categoryRepository.findByIdAndPicksTemplateId(categoryId, templateId)).thenReturn(Optional.of(category));
        when(tmdbClient.searchPeople("Ada", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE, 1))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbSearchPage<>(1,
                        List.of(new TmdbPersonSearchResult("42", "Ada", null)), 2, 21)));
        when(previewAssembler.assemble(eq(List.of(target)), any(ContentCardContext.class)))
                .thenReturn(List.of(preview));

        Page<PickOptionViewDTO> result = service.getOptionViews(viewerId, templateId, categoryId,
                "Ada", null, null, 1, 21);

        assertThat(result.getSize()).isEqualTo(20);
        assertThat(result.getPageable().getPageSize()).isEqualTo(20);
        assertThat(result.getTotalElements()).isEqualTo(21);
        assertThat(result.getTotalPages()).isEqualTo(2);
        verify(previewAssembler).assemble(eq(List.of(target)), any(ContentCardContext.class));
    }

    @Test
    void shouldUseDirectPersonSearchAndPreserveTmdbMetadata() {
        UUID viewerId = UUID.randomUUID();
        PicksTemplateCategory category = category();
        when(categoryRepository.findByIdAndPicksTemplateId(category.getId(), category.getPicksTemplate().getId())).thenReturn(Optional.of(category));
        when(tmdbClient.searchPeople("Ada", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE, 2))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbSearchPage<>(2,
                        List.of(new TmdbPersonSearchResult("42", "Ada", null)), 5, 81)));

        Page<PickOptionSearchDTO> result = service.searchOptions(viewerId, category.getPicksTemplate().getId(), category.getId(), "Ada", null, null, 2, 40);

        assertThat(result.getContent()).extracting(PickOptionSearchDTO::personTmdbId).containsExactly("42");
        assertThat(result.getTotalElements()).isEqualTo(81);
        assertThat(result.getTotalPages()).isEqualTo(5);
        assertThat(result.getNumber()).isEqualTo(1);
        assertThat(result.hasNext()).isTrue();
        verify(requestThrottler).checkAllowed(eq("search|" + viewerId), anyInt(), any());
        verifyNoInteractions(optionRepository);
    }

    @Test
    @DisplayName("[searchOptions] Should Preserve Requested Page Size - When Raw Options Are Requested")
    void shouldPreserveRawOptionPageSizeWhenClientRequestsTwentyOne() {
        PicksTemplateCategory category = category();
        when(categoryRepository.findByIdAndPicksTemplateId(category.getId(), category.getPicksTemplate().getId()))
                .thenReturn(Optional.of(category));
        when(tmdbClient.searchPeople("Ada", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE, 1))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbSearchPage<>(1,
                        List.of(new TmdbPersonSearchResult("42", "Ada", null)), 2, 21)));

        Page<PickOptionSearchDTO> result = service.searchOptions(UUID.randomUUID(),
                category.getPicksTemplate().getId(), category.getId(), "Ada", null, null, 1, 21);

        assertThat(result.getSize()).isEqualTo(21);
        assertThat(result.getPageable().getPageSize()).isEqualTo(21);
        assertThat(result.getTotalElements()).isEqualTo(21);
    }

    @Test
    void shouldPreserveMovieTmdbPageMetadataWithNonDefaultSize() {
        PicksTemplateCategory category = category(PickAllowedType.MOVIE);
        when(categoryRepository.findByIdAndPicksTemplateId(category.getId(), category.getPicksTemplate().getId())).thenReturn(Optional.of(category));
        when(tmdbClient.searchMovies("Fight", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE, 3))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbSearchPage<>(3,
                        java.util.Collections.nCopies(20, new TmdbMovieSearchResult("550", "Fight Club", null, "1999-10-15")), 5, 81)));

        Page<PickOptionSearchDTO> result = service.searchOptions(UUID.randomUUID(), category.getPicksTemplate().getId(), category.getId(), "Fight", null, null, 3, 40);

        assertThat(result.getTotalElements()).isEqualTo(81);
        assertThat(result.getTotalPages()).isEqualTo(5);
        assertThat(result.getNumber()).isEqualTo(2);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.getSize()).isEqualTo(40);
        assertThat(result.getNumberOfElements()).isEqualTo(20);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("[searchOptions] Should Preserve External Total And Requested Size - When Last Page Uses Nondefault Size")
    void shouldPreserveExternalTotalWhenLastPageUsesNondefaultSize() {
        PicksTemplateCategory category = category();
        when(categoryRepository.findByIdAndPicksTemplateId(category.getId(), category.getPicksTemplate().getId()))
                .thenReturn(Optional.of(category));
        when(tmdbClient.searchPeople("Ada", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE, 5))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbSearchPage<>(5,
                        List.of(new TmdbPersonSearchResult("42", "Ada", null)), 5, 81)));

        Page<PickOptionSearchDTO> result = service.searchOptions(UUID.randomUUID(), category.getPicksTemplate().getId(),
                category.getId(), "Ada", null, null, 5, 40);
        var response = com.watchwise.watchwise_api.common.dto.PageResponseDTO.of(result);

        assertThat(response.totalElements()).isEqualTo(81);
        assertThat(response.page()).isEqualTo(5);
        assertThat(response.size()).isEqualTo(40);
        assertThat(response.totalPages()).isEqualTo(5);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.content()).hasSize(1);
    }

    @Test
    void shouldPreserveSeriesTmdbPageMetadataWithSmallNonDefaultSize() {
        PicksTemplateCategory category = category(PickAllowedType.SERIES);
        when(categoryRepository.findByIdAndPicksTemplateId(category.getId(), category.getPicksTemplate().getId())).thenReturn(Optional.of(category));
        when(tmdbClient.searchTv("Breaking", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE, 2))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbSearchPage<>(2,
                        List.of(new TmdbTvSearchResult("1396", "Breaking Bad", null, "2008-01-20")), 5, 81)));

        Page<PickOptionSearchDTO> result = service.searchOptions(UUID.randomUUID(), category.getPicksTemplate().getId(), category.getId(), "Breaking", null, null, 2, 5);

        assertThat(result.getTotalElements()).isEqualTo(81);
        assertThat(result.getTotalPages()).isEqualTo(5);
        assertThat(result.getNumber()).isEqualTo(1);
        assertThat(result.hasNext()).isTrue();
    }

    @Test
    void shouldRejectMissingQueryForOpenPersonSearch() {
        PicksTemplateCategory category = category();
        when(categoryRepository.findByIdAndPicksTemplateId(category.getId(), category.getPicksTemplate().getId())).thenReturn(Optional.of(category));

        assertThatThrownBy(() -> service.searchOptions(UUID.randomUUID(), category.getPicksTemplate().getId(), category.getId(), null, null, null, null, null))
                .isInstanceOf(com.watchwise.watchwise_api.common.exception.BadRequestException.class);
        verifyNoInteractions(tmdbClient);
    }

    @Test
    @DisplayName("[deleteOption] Should Reject - When Deleting The Last Option From An Unused Fixed Category")
    void shouldRejectDeletingLastFixedOptionWhenCategoryHasNoSelections() {
        UUID actorId = UUID.randomUUID();
        PicksTemplateCategory category = fixedCategory();
        PicksTemplateOption option = PicksTemplateOption.builder().id(UUID.randomUUID()).category(category)
                .personTmdbId("42").createdAt(LocalDateTime.now()).build();
        when(templateRepository.findByIdForUpdate(category.getPicksTemplate().getId())).thenReturn(Optional.of(category.getPicksTemplate()));
        when(userRepository.findById(actorId)).thenReturn(Optional.of(User.builder().id(actorId).role(com.watchwise.watchwise_api.user.entity.UserRole.ADMIN).build()));
        when(categoryRepository.findByIdForUpdate(category.getId())).thenReturn(Optional.of(category));
        when(optionRepository.findById(option.getId())).thenReturn(Optional.of(option));
        when(selectionRepository.existsByCategoryIdAndContentIdAndPersonTmdbIdAndContextContentId(
                category.getId(), null, "42", null)).thenReturn(false);
        when(optionRepository.findByCategoryId(category.getId())).thenReturn(List.of(option));

        assertThatThrownBy(() -> service.deleteOption(actorId, category.getPicksTemplate().getId(), category.getId(), option.getId()))
                .isInstanceOf(com.watchwise.watchwise_api.common.exception.ConflictException.class);

        verify(optionRepository, never()).delete(option);
    }

    @Test
    @DisplayName("[addOption] Should Allow - When Repairing An Historical Empty Fixed Category")
    void shouldAllowAddingOptionToHistoricalEmptyFixedCategoryWhenRepairingTemplate() {
        UUID actorId = UUID.randomUUID();
        PicksTemplateCategory category = fixedCategory();
        when(templateRepository.findByIdForUpdate(category.getPicksTemplate().getId())).thenReturn(Optional.of(category.getPicksTemplate()));
        when(userRepository.findById(actorId)).thenReturn(Optional.of(User.builder().id(actorId).role(com.watchwise.watchwise_api.user.entity.UserRole.ADMIN).build()));
        when(categoryRepository.findByIdForUpdate(category.getId())).thenReturn(Optional.of(category));
        ResolvedPickTarget target = new ResolvedPickTarget(null, "42", null, null);
        when(pickTargetService.validateFixedOption(eq(actorId), eq(category.getPicksTemplate()), eq(category), any())).thenReturn(target);
        when(optionRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            PicksTemplateOption saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
        when(optionMapper.picksTemplateOptionToSearchDto(any())).thenReturn(new PickOptionSearchDTO(null, null, "42", null));

        var result = service.addOption(actorId, category.getPicksTemplate().getId(), category.getId(),
                new com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateOptionCreationDTO(new PickTargetDTO(null, "42", null)));

        assertThat(result.target().personTmdbId()).isEqualTo("42");
        verify(optionRepository).saveAndFlush(any());
    }

    @Test
    void shouldKeepFixedLookupOutOfTheThrottleBucketAndAllowTheFollowingOpenSearch() {
        UUID viewerId = UUID.randomUUID();
        PicksTemplateCategory category = fixedCategory();
        PageRequest pageRequest = PageRequest.of(1, 1);
        PicksTemplateOption option = PicksTemplateOption.builder().id(UUID.randomUUID()).category(category).personTmdbId("42")
                .createdAt(LocalDateTime.now()).build();
        when(categoryRepository.findByIdAndPicksTemplateId(category.getId(), category.getPicksTemplate().getId())).thenReturn(Optional.of(category));
        when(optionRepository.findByCategoryId(category.getId(), pageRequest)).thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(option), pageRequest, 2));
        when(optionMapper.picksTemplateOptionToSearchDto(option)).thenReturn(new PickOptionSearchDTO(option.getId(), null, "42", null));
        when(tmdbClient.searchPeople("Ada", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE, 1))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbSearchPage<>(1,
                        List.of(new TmdbPersonSearchResult("84", "Ada", null)), 1, 1)));

        Page<PickOptionSearchDTO> fixedResult = service.searchOptions(viewerId, category.getPicksTemplate().getId(),
                category.getId(), null, null, null, 2, 1);

        assertThat(fixedResult.getTotalElements()).isEqualTo(2);
        verify(optionRepository).findByCategoryId(category.getId(), pageRequest);
        verify(optionRepository, never()).findByCategoryId(category.getId());
        verifyNoInteractions(requestThrottler, tmdbClient);

        category.setOptionMode(PickCategoryOptionMode.OPEN);
        Page<PickOptionSearchDTO> openResult = service.searchOptions(viewerId, category.getPicksTemplate().getId(),
                category.getId(), "Ada", null, null, 1, 20);

        assertThat(openResult.getContent()).extracting(PickOptionSearchDTO::personTmdbId).containsExactly("84");
        InOrder inOrder = inOrder(requestThrottler, tmdbClient);
        inOrder.verify(requestThrottler).checkAllowed(eq("search|" + viewerId), anyInt(), any());
        inOrder.verify(tmdbClient).searchPeople("Ada", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE, 1);
    }

    @Test
    @DisplayName("[getOptionViews] Should Enrich Distinct Fixed Targets In Page Order - When The Database Selects A Later Page")
    void shouldEnrichDistinctFixedTargetsInPageOrderWhenDatabaseSelectsLaterPage() {
        PicksTemplateCategory category = fixedCategory();
        PicksTemplateOption first = PicksTemplateOption.builder().id(UUID.randomUUID()).category(category).personTmdbId("42").build();
        PicksTemplateOption second = PicksTemplateOption.builder().id(UUID.randomUUID()).category(category).personTmdbId("84").build();
        var firstTarget = new PickOptionSearchDTO(first.getId(), null, "42", null);
        var secondTarget = new PickOptionSearchDTO(second.getId(), null, "84", null);
        var firstPreview = new PickTargetPreviewDTO(null, new com.watchwise.watchwise_api.pick.dto.PickPersonPreviewDTO(
                "42", "Ada", null, com.watchwise.watchwise_api.content.dto.ContentPreviewStatus.PARTIAL), null);
        var secondPreview = new PickTargetPreviewDTO(null, new com.watchwise.watchwise_api.pick.dto.PickPersonPreviewDTO(
                "84", "Grace", null, com.watchwise.watchwise_api.content.dto.ContentPreviewStatus.UNAVAILABLE), null);
        when(categoryRepository.findByIdAndPicksTemplateId(category.getId(), category.getPicksTemplate().getId()))
                .thenReturn(Optional.of(category));
        when(optionRepository.findByCategoryId(category.getId(), PageRequest.of(1, 2)))
                .thenReturn(new PageImpl<>(List.of(first, second), PageRequest.of(1, 2), 5));
        when(optionMapper.picksTemplateOptionToSearchDto(first)).thenReturn(firstTarget);
        when(optionMapper.picksTemplateOptionToSearchDto(second)).thenReturn(secondTarget);
        when(previewAssembler.assemble(eq(List.of(firstTarget, secondTarget)), any(ContentCardContext.class)))
                .thenReturn(List.of(firstPreview, secondPreview));

        var result = service.getOptionViews(UUID.randomUUID(), category.getPicksTemplate().getId(), category.getId(),
                null, null, null, 2, 2);

        assertThat(result.getContent()).extracting(option -> option.preview().person().tmdbId()).containsExactly("42", "84");
        assertThat(result.getContent()).extracting(PickOptionViewDTO::id).containsExactly(first.getId(), second.getId());
        assertThat(result.getContent().get(1).preview().person().previewStatus())
                .isEqualTo(com.watchwise.watchwise_api.content.dto.ContentPreviewStatus.UNAVAILABLE);
        assertThat(result.getNumber()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(2);
        assertThat(result.getTotalElements()).isEqualTo(5);
        assertThat(result.getTotalPages()).isEqualTo(3);
        assertThat(result.hasNext()).isTrue();
        verify(previewAssembler).assemble(eq(List.of(firstTarget, secondTarget)), any(ContentCardContext.class));
        verify(optionRepository, never()).findByCategoryId(category.getId());
        verifyNoInteractions(requestThrottler, tmdbClient);
    }

    @Test
    @DisplayName("[getOptionViews] Should Return Content Card Preview - When Persisted Fixed Movie Option Is Read")
    void shouldReturnContentCardPreviewForPersistedFixedMovieOption() {
        PicksTemplateCategory category = fixedCategory();
        Content movie = Content.builder().id(UUID.randomUUID()).tmdbId("550").type(ContentType.MOVIE)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        PicksTemplateOption option = PicksTemplateOption.builder().id(UUID.randomUUID()).category(category)
                .content(movie).createdAt(LocalDateTime.now()).build();
        PickOptionSearchDTO target = new PickOptionSearchDTO(option.getId(),
                new ContentRefDTO(movie.getId(), movie.getTmdbId(), ContentType.MOVIE,
                        null, null, null, null, null, null, null), null, null);
        PickTargetPreviewDTO preview = new PickTargetPreviewDTO(
                new ContentCardDTO(null, ContentType.MOVIE, "550", null, null, null,
                        "Fight Club", null, null, null, null, null, null, null, null,
                        null, null, null, ContentPreviewStatus.AVAILABLE), null, null);
        PageRequest pageRequest = PageRequest.of(0, 20);
        when(categoryRepository.findByIdAndPicksTemplateId(category.getId(), category.getPicksTemplate().getId()))
                .thenReturn(Optional.of(category));
        when(optionRepository.findByCategoryId(category.getId(), pageRequest))
                .thenReturn(new PageImpl<>(List.of(option), pageRequest, 1));
        when(optionMapper.picksTemplateOptionToSearchDto(option)).thenReturn(target);
        when(previewAssembler.assemble(eq(List.of(target)), any(ContentCardContext.class)))
                .thenReturn(List.of(preview));

        Page<PickOptionViewDTO> result = service.getOptionViews(UUID.randomUUID(),
                category.getPicksTemplate().getId(), category.getId(), null, null, null, 1, 20);

        assertThat(result).singleElement().satisfies(view -> {
            assertThat(view.id()).isEqualTo(option.getId());
            assertThat(view.target().content().tmdbId()).isEqualTo("550");
            assertThat(view.preview().contentCard().tmdbId()).isEqualTo("550");
            assertThat(view.preview().contentCard().title()).isEqualTo("Fight Club");
        });
        verify(previewAssembler).assemble(eq(List.of(target)), any(ContentCardContext.class));
        verifyNoInteractions(requestThrottler, tmdbClient);
    }

    @Test
    @DisplayName("[getOptionViews] Should Return Person And Context Previews - When Persisted Fixed Person Option Has Context")
    void shouldReturnPersonAndContextPreviewsForPersistedFixedPersonOption() {
        PicksTemplateCategory category = fixedCategory();
        Content context = Content.builder().id(UUID.randomUUID()).tmdbId("550").type(ContentType.MOVIE)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        PicksTemplateOption option = PicksTemplateOption.builder().id(UUID.randomUUID()).category(category)
                .personTmdbId("42").contextContent(context).createdAt(LocalDateTime.now()).build();
        PickOptionSearchDTO target = new PickOptionSearchDTO(option.getId(), null, "42",
                new ContentRefDTO(context.getId(), context.getTmdbId(), ContentType.MOVIE,
                        null, null, null, null, null, null, null));
        PickTargetPreviewDTO preview = new PickTargetPreviewDTO(null,
                new PickPersonPreviewDTO("42", "Ada", "/ada.jpg", ContentPreviewStatus.AVAILABLE),
                new ContentCardDTO(null, ContentType.MOVIE, "550", null, null, null,
                        "Fight Club", null, null, null, null, null, null, null, null,
                        null, null, null, ContentPreviewStatus.AVAILABLE));
        PageRequest pageRequest = PageRequest.of(0, 20);
        when(categoryRepository.findByIdAndPicksTemplateId(category.getId(), category.getPicksTemplate().getId()))
                .thenReturn(Optional.of(category));
        when(optionRepository.findByCategoryId(category.getId(), pageRequest))
                .thenReturn(new PageImpl<>(List.of(option), pageRequest, 1));
        when(optionMapper.picksTemplateOptionToSearchDto(option)).thenReturn(target);
        when(previewAssembler.assemble(eq(List.of(target)), any(ContentCardContext.class)))
                .thenReturn(List.of(preview));

        Page<PickOptionViewDTO> result = service.getOptionViews(UUID.randomUUID(),
                category.getPicksTemplate().getId(), category.getId(), null, null, null, 1, 20);

        assertThat(result).singleElement().satisfies(view -> {
            assertThat(view.id()).isEqualTo(option.getId());
            assertThat(view.target().personTmdbId()).isEqualTo("42");
            assertThat(view.preview().person().tmdbId()).isEqualTo("42");
            assertThat(view.preview().person().name()).isEqualTo("Ada");
            assertThat(view.preview().contextCard().tmdbId()).isEqualTo("550");
            assertThat(view.preview().contextCard().title()).isEqualTo("Fight Club");
        });
        verify(previewAssembler).assemble(eq(List.of(target)), any(ContentCardContext.class));
        verifyNoInteractions(requestThrottler, tmdbClient);
    }

    @Test
    @DisplayName("[getOptionViews] Should Reject Invalid Pagination Before Enrichment - When Page Or Size Is Invalid")
    void shouldRejectInvalidPaginationBeforeEnrichmentWhenPageOrSizeIsInvalid() {
        PicksTemplateCategory category = category();
        when(categoryRepository.findByIdAndPicksTemplateId(category.getId(), category.getPicksTemplate().getId()))
                .thenReturn(Optional.of(category));

        for (var pagination : List.of(List.of(-1, 2), List.of(1, 0), List.of(1, -1))) {
            assertThatThrownBy(() -> service.getOptionViews(UUID.randomUUID(), category.getPicksTemplate().getId(),
                    category.getId(), "Ada", null, null, pagination.get(0), pagination.get(1)))
                    .isInstanceOf(com.watchwise.watchwise_api.common.exception.BadRequestException.class);
        }

        verifyNoInteractions(optionRepository, tmdbClient, requestThrottler, previewAssembler);
    }

    @Test
    @DisplayName("[getOptionViews] Should Reject Missing Category Before Enrichment - When The Category Does Not Belong To The Template")
    void shouldRejectMissingCategoryBeforeEnrichmentWhenCategoryDoesNotBelongToTemplate() {
        UUID templateId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        when(categoryRepository.findByIdAndPicksTemplateId(categoryId, templateId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOptionViews(UUID.randomUUID(), templateId, categoryId,
                "Ada", null, null, 1, 2))
                .isInstanceOf(com.watchwise.watchwise_api.common.exception.NotFoundException.class)
                .hasMessage("Picks template category not found");

        verifyNoInteractions(optionRepository, tmdbClient, requestThrottler, previewAssembler);
    }

    private PicksTemplateCategory category() {
        return category(PickAllowedType.PERSON);
    }

    private PicksTemplateCategory category(PickAllowedType allowedType) {
        PicksTemplate template = PicksTemplate.builder().id(UUID.randomUUID()).origin(PickOrigin.COMMUNITY).name("Awards")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        return PicksTemplateCategory.builder().id(UUID.randomUUID()).picksTemplate(template).name("Best person")
                .group(PickCategoryGroup.PRIMARY).displayOrder(1).allowedType(allowedType).optionMode(PickCategoryOptionMode.OPEN)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    private PicksTemplateCategory fixedCategory() {
        PicksTemplateCategory category = category();
        category.setOptionMode(PickCategoryOptionMode.FIXED);
        return category;
    }
}
