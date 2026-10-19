package com.watchwise.watchwise_api.search.dto;

import com.watchwise.watchwise_api.search.service.SearchViewScope;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplatePreviewDTO;

import java.util.List;

public record SearchViewDTO(
        SearchViewScope scope,
        String query,
        int page,
        int size,
        int totalPages,
        long totalResults,
        boolean hasNext,
        List<SearchContentViewDTO> contents,
        List<SearchPersonViewDTO> people,
        List<SearchListViewDTO> lists,
        List<PicksTemplatePreviewDTO> templates,
        List<SearchUserViewDTO> users) {

    public SearchViewDTO {
        contents = contents == null ? List.of() : List.copyOf(contents);
        people = people == null ? List.of() : List.copyOf(people);
        lists = lists == null ? List.of() : List.copyOf(lists);
        templates = templates == null ? List.of() : List.copyOf(templates);
        users = users == null ? List.of() : List.copyOf(users);
    }

    public SearchViewDTO(SearchViewScope scope, String query, int page, int size,
            int totalPages, long totalResults, boolean hasNext,
            List<SearchContentViewDTO> contents, List<SearchPersonViewDTO> people) {
        this(scope, query, page, size, totalPages, totalResults, hasNext,
                contents, people, List.of(), List.of(), List.of());
    }
}
