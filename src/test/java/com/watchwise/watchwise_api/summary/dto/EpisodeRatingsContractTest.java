package com.watchwise.watchwise_api.summary.dto;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EpisodeRatingsContractTest {

    @Test
    void episodeScoreExposesAverageAndRatingCountWhileKeepingLegacyConstructor() {
        EpisodeScoreDTO score = new EpisodeScoreDTO(1, 1, 8, 8.5, 2L);
        EpisodeScoreDTO legacyScore = new EpisodeScoreDTO(1, 2, 7);

        assertThat(score.averageScore()).isEqualTo(8.5);
        assertThat(score.ratingCount()).isEqualTo(2L);
        assertThat(legacyScore.score()).isEqualTo(7);
        assertThat(legacyScore.averageScore()).isNull();
        assertThat(legacyScore.ratingCount()).isZero();
    }

    @Test
    void mapResponseExposesPaginationNullableTotalAndAnImmutableSeriesList() {
        EpisodeRatingsMapItemDTO item = new EpisodeRatingsMapItemDTO(
                "1399", 5L, 8, "custom.jpg", "Game of Thrones", 2011, "/poster.jpg");
        List<EpisodeRatingsMapItemDTO> mutableSeries = new ArrayList<>(List.of(item));
        EpisodeRatingsMapResponseDTO response = new EpisodeRatingsMapResponseDTO(
                mutableSeries, 1, 12, 1L, 1, false, 5L, null);
        mutableSeries.clear();

        assertThat(response.series()).containsExactly(item);
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(12);
        assertThat(response.totalElements()).isEqualTo(1L);
        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.totalWatchedEpisodeCount()).isEqualTo(5L);
        assertThat(response.totalEpisodeCount()).isNull();
        assertThat(item.customPosterUrl()).isEqualTo("custom.jpg");
        assertThat(item.title()).isEqualTo("Game of Thrones");
        assertThat(item.releaseYear()).isEqualTo(2011);
        assertThat(item.posterPath()).isEqualTo("/poster.jpg");
        assertThatThrownBy(() -> response.series().clear())
                .isInstanceOf(UnsupportedOperationException.class);

        EpisodeRatingsMapResponseDTO legacyResponse = new EpisodeRatingsMapResponseDTO(List.of(item));
        EpisodeRatingsMapItemDTO legacyItem = new EpisodeRatingsMapItemDTO("1399", 5L, 8);
        assertThat(legacyResponse.series()).containsExactly(item);
        assertThat(legacyItem.customPosterUrl()).isNull();
    }

    @Test
    void gridExposesSeriesMetadataAndOverallRatingsWithAnImmutableEpisodeList() {
        EpisodeScoreDTO episode = new EpisodeScoreDTO(1, 1, 8, 8.5, 2L);
        List<EpisodeScoreDTO> mutableEpisodes = new ArrayList<>(List.of(episode));
        EpisodeRatingsGridResponseDTO response = new EpisodeRatingsGridResponseDTO(
                "1399", mutableEpisodes, "custom.jpg", "Game of Thrones", 2011,
                "/poster.jpg", "/backdrop.jpg", 8.2, 42L);
        mutableEpisodes.clear();

        assertThat(response.episodes()).containsExactly(episode);
        assertThat(response.customPosterUrl()).isEqualTo("custom.jpg");
        assertThat(response.title()).isEqualTo("Game of Thrones");
        assertThat(response.releaseYear()).isEqualTo(2011);
        assertThat(response.posterPath()).isEqualTo("/poster.jpg");
        assertThat(response.backdropPath()).isEqualTo("/backdrop.jpg");
        assertThat(response.overallAverageScore()).isEqualTo(8.2);
        assertThat(response.overallRatingCount()).isEqualTo(42L);
        assertThatThrownBy(() -> response.episodes().clear())
                .isInstanceOf(UnsupportedOperationException.class);

        EpisodeRatingsGridResponseDTO legacyResponse = new EpisodeRatingsGridResponseDTO("1399", List.of());
        assertThat(legacyResponse.seriesTmdbId()).isEqualTo("1399");
    }
}
