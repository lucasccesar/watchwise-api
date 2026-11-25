package com.watchwise.watchwise_api.platformimport;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDate;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbRegionReleaseDates;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

public final class ImportMovieDetailsFixture {
    private ImportMovieDetailsFixture() {
    }

    public static void stubUnavailableDetails(TmdbClient tmdb) {
        String language = TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE;
        when(tmdb.getMovieFullDetails(anyString(), eq(language))).thenReturn(new TmdbLookupResult.Unavailable<>());
        when(tmdb.getTvFullDetails(anyString(), eq(language))).thenReturn(new TmdbLookupResult.Unavailable<>());
        when(tmdb.getEpisodeFullDetails(anyString(), anyInt(), anyInt(), eq(language)))
                .thenReturn(new TmdbLookupResult.Unavailable<>());
    }

    public static void stubReleases(TmdbClient tmdb, List<String> ids) {
        var theatricalRelease = new TmdbMovieReleaseDate(null, null, "2025-06-01T00:00:00.000Z", null, 3);
        var releases = List.of(
                new TmdbRegionReleaseDates("US", List.of(theatricalRelease)),
                new TmdbRegionReleaseDates("BR", List.of(theatricalRelease)));
        for (String id : ids) {
            var details = new TmdbMovieFullDetails(id, "Movie " + id, new TmdbMovieReleaseDates(id, releases));
            when(tmdb.getMovieFullDetails(id, TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                    .thenReturn(new TmdbLookupResult.Found<>(details));
        }
    }
}
