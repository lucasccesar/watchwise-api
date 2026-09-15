package com.watchwise.watchwise_api.pickstemplate.service.impl;

import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.pick.dto.PickOptionSearchDTO;
import com.watchwise.watchwise_api.pick.repository.PickSelectionRepository;
import com.watchwise.watchwise_api.pick.service.PickTargetService;
import com.watchwise.watchwise_api.pickstemplate.entity.*;
import com.watchwise.watchwise_api.pickstemplate.mapper.PicksTemplateOptionMapper;
import com.watchwise.watchwise_api.pickstemplate.repository.*;
import com.watchwise.watchwise_api.search.dto.SearchPersonDTO;
import com.watchwise.watchwise_api.search.dto.SearchResultDTO;
import com.watchwise.watchwise_api.search.service.SearchService;
import com.watchwise.watchwise_api.search.service.SearchType;
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
    @Mock SearchService searchService;
    @Mock TmdbClient tmdbClient;
    @InjectMocks PicksTemplateOptionServiceImpl service;

    @Test
    void shouldUsePersonSearchForOpenPersonCategoryWithoutPersistingOptions() {
        UUID viewerId = UUID.randomUUID();
        PicksTemplateCategory category = category();
        when(categoryRepository.findByIdAndPicksTemplateId(category.getId(), category.getPicksTemplate().getId())).thenReturn(Optional.of(category));
        when(pageRequestFactory.build(null, null)).thenReturn(PageRequest.of(0, 20));
        when(searchService.search(viewerId, "Ada", SearchType.PERSON, 1, 20))
                .thenReturn(new SearchResultDTO(List.of(), List.of(new SearchPersonDTO("42", "Ada", null)), List.of(), List.of()));

        Page<PickOptionSearchDTO> result = service.searchOptions(viewerId, category.getPicksTemplate().getId(), category.getId(), "Ada", null, null, null, null);

        assertThat(result.getContent()).extracting(PickOptionSearchDTO::personTmdbId).containsExactly("42");
        verifyNoInteractions(optionRepository);
    }

    private PicksTemplateCategory category() {
        PicksTemplate template = PicksTemplate.builder().id(UUID.randomUUID()).origin(PickOrigin.COMMUNITY).name("Awards")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        return PicksTemplateCategory.builder().id(UUID.randomUUID()).picksTemplate(template).name("Best person")
                .group(PickCategoryGroup.PRIMARY).displayOrder(1).allowedType(PickAllowedType.PERSON).optionMode(PickCategoryOptionMode.OPEN)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }
}
