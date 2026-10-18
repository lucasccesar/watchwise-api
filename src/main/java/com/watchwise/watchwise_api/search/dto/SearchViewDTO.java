package com.watchwise.watchwise_api.search.dto;

import com.watchwise.watchwise_api.search.service.SearchViewScope;

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
        List<SearchPersonViewDTO> people) {
}
