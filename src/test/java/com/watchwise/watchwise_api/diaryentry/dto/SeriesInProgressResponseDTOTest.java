package com.watchwise.watchwise_api.diaryentry.dto;

import com.watchwise.watchwise_api.seriesprogress.dto.ProgressEpisodeDTO;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SeriesInProgressResponseDTOTest {

    @Test
    void shouldAddPresentationDataWithoutChangingExistingProgressValues() {
        SeriesInProgressResponseDTO base = new SeriesInProgressResponseDTO(
                "1399", 5, 14, LocalDate.of(2026, 9, 21), 14L, 16, 87.5,
                List.of());

        ProgressEpisodeDTO nextEpisode = new ProgressEpisodeDTO(
                5, 15, "Granite State", LocalDate.of(2013, 9, 22), 55,
                "/still.jpg", true);

        SeriesInProgressResponseDTO enriched = base.withPresentation(
                "Breaking Bad", "/poster.jpg", "Ozymandias", nextEpisode);

        assertThat(enriched.seriesTitle()).isEqualTo("Breaking Bad");
        assertThat(enriched.seriesPosterPath()).isEqualTo("/poster.jpg");
        assertThat(enriched.lastWatchedEpisodeTitle()).isEqualTo("Ozymandias");
        assertThat(enriched.nextEpisode()).isEqualTo(nextEpisode);
        assertThat(enriched.watchedEpisodeCount()).isEqualTo(14L);
        assertThat(enriched.totalEpisodeCount()).isEqualTo(16);
        assertThat(enriched.watchedPercentage()).isEqualTo(87.5);
    }

    @Test
    void shouldKeepPresentationFieldsNullInCompatibilityConstructor() {
        SeriesInProgressResponseDTO response = new SeriesInProgressResponseDTO(
                "1399", 5, 14, LocalDate.of(2026, 9, 21), 14L, 16, 87.5);

        assertThat(response.seriesTitle()).isNull();
        assertThat(response.seriesPosterPath()).isNull();
        assertThat(response.lastWatchedEpisodeTitle()).isNull();
        assertThat(response.nextEpisode()).isNull();
    }
}
