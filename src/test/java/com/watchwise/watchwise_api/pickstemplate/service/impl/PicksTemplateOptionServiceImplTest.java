package com.watchwise.watchwise_api.pickstemplate.service.impl;

import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSearchPage;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvSearchResult;
import com.watchwise.watchwise_api.pick.dto.PickOptionSearchDTO;
import com.watchwise.watchwise_api.pick.repository.PickSelectionRepository;
import com.watchwise.watchwise_api.pick.service.PickTargetService;
import com.watchwise.watchwise_api.pickstemplate.entity.*;
import com.watchwise.watchwise_api.pickstemplate.mapper.PicksTemplateOptionMapper;
import com.watchwise.watchwise_api.pickstemplate.repository.*;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

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
    @Mock PageRequestFactory pageRequestFactory;
    @Mock TmdbClient tmdbClient;
    @Mock RequestThrottler requestThrottler;
    @InjectMocks PicksTemplateOptionServiceImpl service;

    @Test
    void shouldUseDirectPersonSearchAndPreserveTmdbMetadata() {
        UUID viewerId = UUID.randomUUID();
        PicksTemplateCategory category = category();
        when(categoryRepository.findByIdAndPicksTemplateId(category.getId(), category.getPicksTemplate().getId())).thenReturn(Optional.of(category));
        when(pageRequestFactory.build(2, 40)).thenReturn(PageRequest.of(1, 40));
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
    void shouldPreserveMovieTmdbPageMetadataWithNonDefaultSize() {
        PicksTemplateCategory category = category(PickAllowedType.MOVIE);
        when(categoryRepository.findByIdAndPicksTemplateId(category.getId(), category.getPicksTemplate().getId())).thenReturn(Optional.of(category));
        when(pageRequestFactory.build(3, 40)).thenReturn(PageRequest.of(2, 40));
        when(tmdbClient.searchMovies("Fight", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE, 3))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbSearchPage<>(3,
                        List.of(new TmdbMovieSearchResult("550", "Fight Club", null, "1999-10-15")), 5, 81)));

        Page<PickOptionSearchDTO> result = service.searchOptions(UUID.randomUUID(), category.getPicksTemplate().getId(), category.getId(), "Fight", null, null, 3, 40);

        assertThat(result.getTotalElements()).isEqualTo(81);
        assertThat(result.getTotalPages()).isEqualTo(5);
        assertThat(result.getNumber()).isEqualTo(2);
        assertThat(result.hasNext()).isTrue();
    }

    @Test
    void shouldPreserveSeriesTmdbPageMetadataWithSmallNonDefaultSize() {
        PicksTemplateCategory category = category(PickAllowedType.SERIES);
        when(categoryRepository.findByIdAndPicksTemplateId(category.getId(), category.getPicksTemplate().getId())).thenReturn(Optional.of(category));
        when(pageRequestFactory.build(2, 5)).thenReturn(PageRequest.of(1, 5));
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
        when(pageRequestFactory.build(null, null)).thenReturn(PageRequest.of(0, 20));

        assertThatThrownBy(() -> service.searchOptions(UUID.randomUUID(), category.getPicksTemplate().getId(), category.getId(), null, null, null, null, null))
                .isInstanceOf(com.watchwise.watchwise_api.common.exception.BadRequestException.class);
        verifyNoInteractions(tmdbClient);
    }

    @Test
    void shouldKeepFixedLookupOutOfTheThrottleBucketAndAllowTheFollowingOpenSearch() {
        UUID viewerId = UUID.randomUUID();
        PicksTemplateCategory category = fixedCategory();
        PageRequest pageRequest = PageRequest.of(1, 1);
        PageRequest openPageRequest = PageRequest.of(0, 20);
        PicksTemplateOption option = PicksTemplateOption.builder().id(UUID.randomUUID()).category(category).personTmdbId("42")
                .createdAt(LocalDateTime.now()).build();
        when(categoryRepository.findByIdAndPicksTemplateId(category.getId(), category.getPicksTemplate().getId())).thenReturn(Optional.of(category));
        when(pageRequestFactory.build(2, 1)).thenReturn(pageRequest);
        when(pageRequestFactory.build(1, 20)).thenReturn(openPageRequest);
        when(optionRepository.findByCategoryId(category.getId(), pageRequest)).thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(option), pageRequest, 2));
        when(optionMapper.picksTemplateOptionToSearchDto(option)).thenReturn(new PickOptionSearchDTO(option.getId(), null, "42", null));
        when(tmdbClient.searchPeople("Ada", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE, 1))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbSearchPage<>(1,
                        List.of(new TmdbPersonSearchResult("84", "Ada", null)), 1, 1)));

        Page<PickOptionSearchDTO> fixedResult = service.searchOptions(viewerId, category.getPicksTemplate().getId(),
                category.getId(), null, null, null, 2, 1);
        category.setOptionMode(PickCategoryOptionMode.OPEN);
        Page<PickOptionSearchDTO> openResult = service.searchOptions(viewerId, category.getPicksTemplate().getId(),
                category.getId(), "Ada", null, null, 1, 20);

        assertThat(fixedResult.getTotalElements()).isEqualTo(2);
        assertThat(openResult.getContent()).extracting(PickOptionSearchDTO::personTmdbId).containsExactly("84");
        verify(optionRepository).findByCategoryId(category.getId(), pageRequest);
        verify(optionRepository, never()).findByCategoryId(category.getId());
        InOrder inOrder = inOrder(requestThrottler, tmdbClient);
        inOrder.verify(requestThrottler).checkAllowed(eq("search|" + viewerId), anyInt(), any());
        inOrder.verify(tmdbClient).searchPeople("Ada", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE, 1);
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
