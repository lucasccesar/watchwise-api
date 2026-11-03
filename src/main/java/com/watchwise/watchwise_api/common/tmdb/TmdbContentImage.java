package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbContentImage(
        @JsonProperty("file_path") String filePath,
        Integer width,
        Integer height,
        @JsonProperty("aspect_ratio") Double aspectRatio,
        @JsonProperty("iso_639_1") String language,
        @JsonProperty("vote_average") Double voteAverage,
        @JsonProperty("vote_count") Integer voteCount) {
}
