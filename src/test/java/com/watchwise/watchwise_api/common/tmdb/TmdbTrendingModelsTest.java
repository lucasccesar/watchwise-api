package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TmdbTrendingModelsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldParseTrendingMovieFieldsWhenTmdbReturnsMovieResult() throws Exception {
        TmdbTrendingMovieResult result = objectMapper.readValue("""
                {"id":603,"title":"The Matrix","poster_path":"/matrix.jpg",
                 "release_date":"1999-03-31","genre_ids":[28,878],
                 "vote_average":8.7,"popularity":123.4}
                """, TmdbTrendingMovieResult.class);

        assertThat(result).isEqualTo(new TmdbTrendingMovieResult(
                "603", "The Matrix", "/matrix.jpg", "1999-03-31",
                java.util.List.of(28, 878), 8.7, 123.4));
    }

    @Test
    void shouldParseTrendingTvFieldsWhenTmdbReturnsTvResult() throws Exception {
        TmdbTrendingTvResult result = objectMapper.readValue("""
                {"id":1396,"name":"Breaking Bad","poster_path":"/breaking-bad.jpg",
                 "first_air_date":"2008-01-20","genre_ids":[18,80],
                 "vote_average":9.1,"popularity":456.7}
                """, TmdbTrendingTvResult.class);

        assertThat(result).isEqualTo(new TmdbTrendingTvResult(
                "1396", "Breaking Bad", "/breaking-bad.jpg", "2008-01-20",
                java.util.List.of(18, 80), 9.1, 456.7));
    }
}
