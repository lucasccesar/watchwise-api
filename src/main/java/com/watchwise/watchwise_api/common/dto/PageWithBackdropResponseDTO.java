package com.watchwise.watchwise_api.common.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record PageWithBackdropResponseDTO<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        String backdropPath) {

    public PageWithBackdropResponseDTO {
        content = content == null ? List.of() : List.copyOf(content);
    }

    public static <T> PageWithBackdropResponseDTO<T> of(Page<T> page, String backdropPath) {
        return new PageWithBackdropResponseDTO<>(
                page.getContent(), page.getNumber() + 1, page.getSize(), page.getTotalElements(),
                page.getTotalPages(), page.hasNext(), backdropPath);
    }
}
