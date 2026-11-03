package com.watchwise.watchwise_api.content.dto;

public record ContentImageDTO(
        String url,
        Integer width,
        Integer height,
        Double aspectRatio,
        String language,
        Double voteAverage,
        Integer voteCount) {
}
