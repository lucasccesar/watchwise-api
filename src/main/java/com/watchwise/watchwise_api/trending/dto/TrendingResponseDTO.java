package com.watchwise.watchwise_api.trending.dto;

import com.watchwise.watchwise_api.search.dto.SearchContentDTO;

import java.util.List;

public record TrendingResponseDTO(
        List<SearchContentDTO> movies,
        List<SearchContentDTO> series) {
}
