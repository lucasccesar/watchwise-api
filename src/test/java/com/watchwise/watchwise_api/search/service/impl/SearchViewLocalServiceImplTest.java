package com.watchwise.watchwise_api.search.service.impl;

import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplatePreviewDTO;
import com.watchwise.watchwise_api.pickstemplate.entity.PickOrigin;
import com.watchwise.watchwise_api.pickstemplate.service.PicksTemplateService;
import com.watchwise.watchwise_api.search.dto.SearchListViewDTO;
import com.watchwise.watchwise_api.search.dto.SearchUserViewDTO;
import com.watchwise.watchwise_api.search.dto.SearchViewDTO;
import com.watchwise.watchwise_api.search.service.SearchViewQuery;
import com.watchwise.watchwise_api.search.service.SearchViewScope;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.service.UserListViewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchViewLocalServiceImplTest {

    private static final UUID VIEWER_ID = UUID.randomUUID();

    @Mock UserRepository users;
    @Mock TmdbClient tmdbClient;
    @Mock SearchContentViewAssembler contentAssembler;
    @Mock UserListViewService listViewService;
    @Mock SearchListViewAssembler listAssembler;
    @Mock PicksTemplateService templateService;
    @Mock SearchUserViewReader userReader;
    @Mock PageRequestFactory pages;

    private SearchViewServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SearchViewServiceImpl(users, tmdbClient, contentAssembler,
                listViewService, listAssembler, templateService, userReader, pages);
        when(users.findById(VIEWER_ID)).thenReturn(Optional.of(User.builder().id(VIEWER_ID).build()));
    }

    @Test
    void shouldReturnPaginatedListScope() {
        PageRequest pageable = PageRequest.of(0, 10);
        when(listViewService.getView(any())).thenReturn(new PageImpl<>(List.of(), pageable, 12));
        when(listAssembler.assemble(List.of())).thenReturn(List.of());

        SearchViewDTO result = service.search(new SearchViewQuery(
                VIEWER_ID, "night", SearchViewScope.LIST, 1, 10, null, null));

        assertThat(result.lists()).isEmpty();
        assertThat(result.totalResults()).isEqualTo(12);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.hasNext()).isTrue();
    }

    @Test
    void shouldPassTemplateOriginFilterToTemplateReader() {
        PageRequest pageable = PageRequest.of(0, 10);
        when(templateService.searchForView(VIEWER_ID, PickOrigin.COMMUNITY, "night", 1, 10))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        SearchViewDTO result = service.search(new SearchViewQuery(
                VIEWER_ID, "night", SearchViewScope.PICKS_TEMPLATE, 1, 10, PickOrigin.COMMUNITY, null));

        assertThat(result.templates()).isEmpty();
        assertThat(result.totalResults()).isZero();
    }

    @Test
    void shouldReturnUserCardsAndPageMetadata() {
        PageRequest pageable = PageRequest.of(0, 10);
        SearchUserViewDTO card = new SearchUserViewDTO(
                UUID.randomUUID(), "Night User", "night", null, null, true,
                4L, 2L, 1L, false, null, false);
        when(pages.build(1, 10, 20)).thenReturn(pageable);
        when(userReader.read(VIEWER_ID, "night", pageable))
                .thenReturn(new PageImpl<>(List.of(card), pageable, 1));

        SearchViewDTO result = service.search(new SearchViewQuery(
                VIEWER_ID, "night", SearchViewScope.USER, 1, 10, null, null));

        assertThat(result.users()).containsExactly(card);
        assertThat(result.totalResults()).isEqualTo(1);
        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.hasNext()).isFalse();
    }
}
