package com.watchwise.watchwise_api.pickstemplate.service.impl;

import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSearchPage;
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
    @InjectMocks PicksTemplateOptionServiceImpl service;

    @Test
    void shouldUseDirectPersonSearchAndPreserveTmdbMetadata() {
        UUID viewerId = UUID.randomUUID();
        PicksTemplateCategory category = category();
        when(categoryRepository.findByIdAndPicksTemplateId(category.getId(), category.getPicksTemplate().getId())).thenReturn(Optional.of(category));
        when(pageRequestFactory.build(2, 20)).thenReturn(PageRequest.of(1, 20));
        when(tmdbClient.searchPeople("Ada", TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE, 2))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbSearchPage<>(2,
                        List.of(new TmdbPersonSearchResult("42", "Ada", null)), 5, 81)));

        Page<PickOptionSearchDTO> result = service.searchOptions(viewerId, category.getPicksTemplate().getId(), category.getId(), "Ada", null, null, 2, 20);

        assertThat(result.getContent()).extracting(PickOptionSearchDTO::personTmdbId).containsExactly("42");
        assertThat(result.getTotalElements()).isEqualTo(81);
        assertThat(result.getNumber()).isEqualTo(1);
        assertThat(result.hasNext()).isTrue();
        verifyNoInteractions(optionRepository);
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
    void shouldUseRepositoryPageableForFixedOptions() {
        PicksTemplateCategory category = fixedCategory();
        PageRequest pageRequest = PageRequest.of(1, 1);
        PicksTemplateOption option = PicksTemplateOption.builder().id(UUID.randomUUID()).category(category).personTmdbId("42")
                .createdAt(LocalDateTime.now()).build();
        when(categoryRepository.findByIdAndPicksTemplateId(category.getId(), category.getPicksTemplate().getId())).thenReturn(Optional.of(category));
        when(pageRequestFactory.build(2, 1)).thenReturn(pageRequest);
        when(optionRepository.findByCategoryId(category.getId(), pageRequest)).thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(option), pageRequest, 2));
        when(optionMapper.picksTemplateOptionToSearchDto(option)).thenReturn(new PickOptionSearchDTO(option.getId(), null, "42", null));

        Page<PickOptionSearchDTO> result = service.searchOptions(UUID.randomUUID(), category.getPicksTemplate().getId(), category.getId(), null, null, null, 2, 1);

        assertThat(result.getTotalElements()).isEqualTo(2);
        verify(optionRepository).findByCategoryId(category.getId(), pageRequest);
        verify(optionRepository, never()).findByCategoryId(category.getId());
    }

    private PicksTemplateCategory category() {
        PicksTemplate template = PicksTemplate.builder().id(UUID.randomUUID()).origin(PickOrigin.COMMUNITY).name("Awards")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        return PicksTemplateCategory.builder().id(UUID.randomUUID()).picksTemplate(template).name("Best person")
                .group(PickCategoryGroup.PRIMARY).displayOrder(1).allowedType(PickAllowedType.PERSON).optionMode(PickCategoryOptionMode.OPEN)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    private PicksTemplateCategory fixedCategory() {
        PicksTemplateCategory category = category();
        category.setOptionMode(PickCategoryOptionMode.FIXED);
        return category;
    }
}
