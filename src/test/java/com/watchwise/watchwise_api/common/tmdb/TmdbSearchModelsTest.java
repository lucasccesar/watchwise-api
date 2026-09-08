package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TmdbSearchModelsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("[TmdbSearchPage] Should Parse Movie Fields - When TMDB Returns A Movie Search Page")
    void shouldParseMovieFieldsWhenTmdbReturnsAMovieSearchPage() throws Exception {
        String json = """
                {"page":1,"results":[{"id":603,"title":"The Matrix","poster_path":"/matrix.jpg","release_date":"1999-03-31","ignored":"value"}],"total_pages":1,"total_results":1}
                """;

        TmdbSearchPage<TmdbMovieSearchResult> page = readPage(json, TmdbMovieSearchResult.class);

        assertThat(page.results()).extracting("id", "title", "posterPath", "releaseDate")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("603", "The Matrix", "/matrix.jpg", "1999-03-31"));
    }

    @Test
    @DisplayName("[TmdbSearchPage] Should Parse Series Fields - When TMDB Returns A Series Search Page")
    void shouldParseSeriesFieldsWhenTmdbReturnsASeriesSearchPage() throws Exception {
        String json = """
                {"page":1,"results":[{"id":1396,"name":"Breaking Bad","poster_path":"/breaking-bad.jpg","first_air_date":"2008-01-20"}],"total_pages":1,"total_results":1}
                """;

        TmdbSearchPage<TmdbTvSearchResult> page = readPage(json, TmdbTvSearchResult.class);

        assertThat(page.results()).extracting("id", "name", "posterPath", "firstAirDate")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("1396", "Breaking Bad", "/breaking-bad.jpg", "2008-01-20"));
    }

    @Test
    @DisplayName("[TmdbSearchPage] Should Parse Person Fields - When TMDB Returns A Person Search Page")
    void shouldParsePersonFieldsWhenTmdbReturnsAPersonSearchPage() throws Exception {
        String json = """
                {"page":1,"results":[{"id":287,"name":"Keanu Reeves","profile_path":"/keanu.jpg"}],"total_pages":1,"total_results":1}
                """;

        TmdbSearchPage<TmdbPersonSearchResult> page = readPage(json, TmdbPersonSearchResult.class);

        assertThat(page.results()).extracting("id", "name", "profilePath")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("287", "Keanu Reeves", "/keanu.jpg"));
    }

    @Test
    @DisplayName("[TmdbSearchPage] Should Preserve Mixed Result Order - When TMDB Returns A Multi Search Page")
    void shouldPreserveMixedResultOrderWhenTmdbReturnsAMultiSearchPage() throws Exception {
        String json = """
                {"page":2,"results":[
                {"id":603,"media_type":"movie","title":"The Matrix","poster_path":"/matrix.jpg","release_date":"1999-03-31"},
                {"id":287,"media_type":"person","name":"Keanu Reeves","profile_path":"/keanu.jpg"},
                {"id":1396,"media_type":"tv","name":"Breaking Bad","poster_path":"/breaking-bad.jpg","first_air_date":"2008-01-20"}
                ],"total_pages":7,"total_results":123}
                """;

        TmdbSearchPage<TmdbMultiSearchResult> page = readPage(json, TmdbMultiSearchResult.class);

        assertThat(page.page()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(7);
        assertThat(page.totalResults()).isEqualTo(123L);
        assertThat(page.results()).extracting(TmdbMultiSearchResult::mediaType)
                .containsExactly("movie", "person", "tv");
        assertThat(page.results()).extracting(TmdbMultiSearchResult::id)
                .containsExactly("603", "287", "1396");
    }

    @Test
    @DisplayName("[TmdbSearchCacheKey] Should Normalize Query - When Equivalent Query Spellings Are Used")
    void shouldNormalizeQueryWhenEquivalentQuerySpellingsAreUsed() {
        TmdbSearchCacheKey paddedQuery = TmdbSearchCacheKey.of(" Matrix ", TmdbSearchType.MOVIE, "en-US", 1);
        TmdbSearchCacheKey normalizedQuery = TmdbSearchCacheKey.of("matrix", TmdbSearchType.MOVIE, "en-US", 1);

        assertThat(paddedQuery).isEqualTo(normalizedQuery);
    }

    private <T> TmdbSearchPage<T> readPage(String json, Class<T> resultType) throws Exception {
        return objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructParametricType(TmdbSearchPage.class, resultType));
    }
}
