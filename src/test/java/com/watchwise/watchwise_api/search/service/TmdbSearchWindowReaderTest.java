package com.watchwise.watchwise_api.search.service;

import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSearchPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TmdbSearchWindowReaderTest {

    private final TmdbSearchWindowReader reader = new TmdbSearchWindowReader();

    @Test
    @DisplayName("[readPage] Should Read The Exact Visual Page - When The Source Uses Fixed-Size Pages")
    void shouldReadExactVisualPageWhenSourceUsesFixedSizePages() {
        List<Integer> requestedSourcePages = new ArrayList<>();
        IntFunction<TmdbLookupResult<TmdbSearchPage<TmdbMovieSearchResult>>> loader =
                sourcePage -> {
                    requestedSourcePages.add(sourcePage);
                    return found(tmdbPage(sourcePage, 22,
                            sourcePage == 1 ? movies(1, 20) : movies(21, 2)));
                };

        TmdbSearchWindowReader.Window<TmdbMovieSearchResult> window =
                reader.readPage(2, 18, loader);

        assertThat(window.results()).extracting(TmdbMovieSearchResult::id)
                .containsExactly("19", "20", "21", "22");
        assertThat(window.totalResults()).isEqualTo(22L);
        assertThat(window.totalPages()).isEqualTo(2);
        assertThat(window.hasNext()).isFalse();
        assertThat(requestedSourcePages).containsExactly(1, 2);
    }

    @Test
    @DisplayName("[readRange] Should Read A Zero-Based Source Range And Return Total - When The Range Spans Source Pages")
    void shouldReadRangeByZeroBasedSourceIndexAndReturnTotalWhenRangeSpansSourcePages() {
        List<Integer> requestedSourcePages = new ArrayList<>();
        IntFunction<TmdbLookupResult<TmdbSearchPage<TmdbMovieSearchResult>>> loader =
                sourcePage -> {
                    requestedSourcePages.add(sourcePage);
                    return found(tmdbPage(sourcePage, 25,
                            sourcePage == 1 ? movies(1, 20) : movies(21, 5)));
                };

        TmdbSearchWindowReader.Range<TmdbMovieSearchResult> range =
                reader.readRange(17, 8, loader);

        assertThat(range.results()).extracting(TmdbMovieSearchResult::id)
                .containsExactly("18", "19", "20", "21", "22", "23", "24", "25");
        assertThat(range.totalResults()).isEqualTo(25L);
        assertThat(requestedSourcePages).containsExactly(1, 2);
    }

    @Test
    @DisplayName("[readRange] Should Preserve Source Indexes - When A Request Range Has A Missing Middle Rank")
    void shouldPreserveSourceIndexesWhenARequestRangeHasAMissingMiddleRank() {
        TmdbSearchWindowReader.Range<TmdbMovieSearchResult> range = reader.readRange(
                18,
                4,
                sourcePage -> found(tmdbPage(
                        sourcePage,
                        22,
                        sourcePage == 1 ? movies(1, 19) : movies(21, 2))));

        assertThat(range.resultsBySourceIndex()).containsOnlyKeys(18L, 20L, 21L);
        assertThat(range.resultsBySourceIndex().get(18L).id()).isEqualTo("19");
        assertThat(range.resultsBySourceIndex().get(20L).id()).isEqualTo("21");
        assertThat(range.resultsBySourceIndex().get(21L).id()).isEqualTo("22");
        assertThat(range.resultsBySourceIndex()).doesNotContainKey(19L);
    }

    @Test
    @DisplayName("[readPage] Should Return Empty Visual Metadata - When The First Source Page Is Empty")
    void shouldReturnEmptyVisualMetadataWhenFirstPageIsEmpty() {
        TmdbSearchWindowReader.Window<TmdbMovieSearchResult> window = reader.readPage(
                1, 18, sourcePage -> found(tmdbPage(1, 0, List.of())));

        assertThat(window.results()).isEmpty();
        assertThat(window.totalResults()).isZero();
        assertThat(window.totalPages()).isZero();
        assertThat(window.hasNext()).isFalse();
    }

    @Test
    @DisplayName("[readPage] Should Not Request Pages Past The Known Total - When The Requested Page Is Out Of Range")
    void shouldNotRequestSourcePagesPastTheKnownTotalWhenRequestedPageIsOutOfRange() {
        List<Integer> requestedSourcePages = new ArrayList<>();

        TmdbSearchWindowReader.Window<TmdbMovieSearchResult> window = reader.readPage(
                500, 18, sourcePage -> {
                    requestedSourcePages.add(sourcePage);
                    return found(tmdbPage(1, 1, movies(1, 1)));
                });

        assertThat(window.results()).isEmpty();
        assertThat(window.totalResults()).isEqualTo(1L);
        assertThat(requestedSourcePages).containsExactly(1);
    }

    @Test
    @DisplayName("[readPage] Should Cap Reported Pages - When The Visual Page Reaches Maximum")
    void shouldCapReportedPagesWhenVisualPageReachesMaximum() {
        TmdbSearchWindowReader.Window<TmdbMovieSearchResult> window = reader.readPage(
                500, 20, sourcePage -> found(tmdbPage(
                        sourcePage,
                        10_001,
                        sourcePage == 1 ? movies(1, 20) : movies(9_981, 20))));

        assertThat(window.totalResults()).isEqualTo(10_001L);
        assertThat(window.totalPages()).isEqualTo(500);
        assertThat(window.hasNext()).isFalse();
    }

    @Test
    @DisplayName("[readPage] Should Throw TmdbUnavailableException - When The First Source Page Is Unavailable")
    void shouldThrowWhenTheFirstSourcePageIsUnavailable() {
        assertThatThrownBy(() -> reader.readPage(1, 18, page -> unavailable()))
                .isInstanceOf(TmdbUnavailableException.class);
    }

    @Test
    @DisplayName("[readPage] Should Throw TmdbUnavailableException - When A Needed Source Page Is Unavailable")
    void shouldThrowWhenANeededSourcePageIsUnavailable() {
        IntFunction<TmdbLookupResult<TmdbSearchPage<TmdbMovieSearchResult>>> loader = sourcePage ->
                sourcePage == 1
                        ? found(tmdbPage(1, 21, movies(1, 20)))
                        : unavailable();

        assertThatThrownBy(() -> reader.readPage(2, 18, loader))
                .isInstanceOf(TmdbUnavailableException.class);
    }

    private static List<TmdbMovieSearchResult> movies(int firstId, int count) {
        List<TmdbMovieSearchResult> movies = new ArrayList<>();
        for (int offset = 0; offset < count; offset++) {
            int id = firstId + offset;
            movies.add(new TmdbMovieSearchResult(String.valueOf(id), "Movie " + id, null, null));
        }
        return movies;
    }

    private static TmdbSearchPage<TmdbMovieSearchResult> tmdbPage(
            int sourcePage, long totalResults, List<TmdbMovieSearchResult> results) {
        int totalPages = (int) ((totalResults + 19) / 20);
        return new TmdbSearchPage<>(sourcePage, results, totalPages, totalResults);
    }

    private static <T> TmdbLookupResult<T> found(T value) {
        return new TmdbLookupResult.Found<>(value);
    }

    private static <T> TmdbLookupResult<T> unavailable() {
        return new TmdbLookupResult.Unavailable<>();
    }
}
