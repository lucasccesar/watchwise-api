package com.watchwise.watchwise_api.person.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbPersonAggregate;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonAggregateCredit;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonAggregateCredits;
import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import com.watchwise.watchwise_api.person.entity.PersonParticipation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PersonFilmographyNormalizerTest {
    private final PersonFilmographyNormalizer normalizer = new PersonFilmographyNormalizer();

    @Test
    void mergesCastAndCrewKeepingRolesAndFirstUsableVisualAndSortMetadata() {
        var result = normalizer.normalize(new TmdbPersonAggregate("7", "Person", null, null, null, null, null, null,
                null, null, new TmdbPersonAggregateCredits(
                List.of(credit("1", "movie", "Film", null, null, null, "First", null, 5, 4.5),
                        credit("1", "movie", "Film", null, null, null, "Second", null, null, null),
                        credit("2", "tv", null, "Series", null, "/series-backdrop.jpg", null, null, 2, 3.2)),
                List.of(credit("1", "movie", "Film", null, "/poster.jpg", "/film-backdrop.jpg", null, "Director", 7, 8.1),
                        credit("1", "movie", "Film", null, null, null, null, "Producer", null, null),
                        credit("bad", "movie", "Bad", null, null, null, null, "Writer", 2, 1.0),
                        credit("3x", "tv", null, "Bad TV", null, null, null, "Writer", 2, 1.0)))));

        assertThat(result).hasSize(2);
        var film = result.getFirst();
        assertThat(film.type()).isEqualTo(MovieOrSeriesType.MOVIE);
        assertThat(film.tmdbId()).isEqualTo("1");
        assertThat(film.characters()).containsExactly("First", "Second");
        assertThat(film.jobs()).containsExactly("Director", "Producer");
        assertThat(film.backdropPath()).isEqualTo("/film-backdrop.jpg");
        assertThat(film.popularity()).isEqualTo(4.5);
        assertThat(film.creditedEpisodeCount()).isEqualTo(5);
        assertThat(film.participation()).isEqualTo(PersonParticipation.ALL);
        assertThat(result.get(1).type()).isEqualTo(MovieOrSeriesType.SERIES);
    }

    @Test
    void returnsImmutableEmptyCreditsWhenAggregateHasNoCredits() {
        var result = normalizer.normalize(new TmdbPersonAggregate("7", "Person", null, null, null, null, null, null,
                null, null, null));
        assertThat(result).isEmpty();
        assertThrownByAdd(result);
    }

    private static void assertThrownByAdd(List<?> result) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ((List<Object>) result).add(new Object()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static TmdbPersonAggregateCredit credit(String id, String type, String title, String name, String poster,
                                                     String backdrop, String character, String job,
                                                     Integer episodes, Double popularity) {
        return new TmdbPersonAggregateCredit(id, type, title, name, poster, null, null, character, job,
                null, episodes, null, backdrop, popularity);
    }
}
