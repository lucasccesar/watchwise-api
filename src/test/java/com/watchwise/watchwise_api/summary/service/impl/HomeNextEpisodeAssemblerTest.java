package com.watchwise.watchwise_api.summary.service.impl;

import com.watchwise.watchwise_api.content.dto.ContentStatsResponseDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentStatsService;
import com.watchwise.watchwise_api.diaryentry.dto.SeriesInProgressResponseDTO;
import com.watchwise.watchwise_api.seriesprogress.dto.ProgressEpisodeDTO;
import com.watchwise.watchwise_api.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class HomeNextEpisodeAssemblerTest {

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private ContentStatsService contentStatsService;

    @InjectMocks
    private HomeNextEpisodeAssembler assembler;

    @Test
    @DisplayName("[assemble] Should Return Next Episode Metadata And Progress - When Next Episode Exists")
    void shouldReturnNextEpisodeMetadataAndProgressWhenNextEpisodeExists() {
        UUID contentId = UUID.randomUUID();
        User user = User.builder().preferredLanguage("en-US").build();
        SeriesInProgressResponseDTO progress = new SeriesInProgressResponseDTO(
                "1399", 2, 9, null, 12L, 24, 50.0)
                .withPresentation(
                        "The Last of Us", "/poster.jpg", null,
                        new ProgressEpisodeDTO(2, 5, "Next episode", java.time.LocalDate.of(2026, 10, 10),
                                55, "/still.jpg", false));
        Content content = Content.builder().id(contentId).seriesTmdbId("1399").seasonNumber(2)
                .episodeNumber(5).build();
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType(
                "1399", 2, 5, com.watchwise.watchwise_api.content.entity.ContentType.EPISODE))
                .thenReturn(Optional.of(content));
        when(contentStatsService.getStats(contentId)).thenReturn(
                new ContentStatsResponseDTO(contentId, 8.5, 10, 2, 0));

        var result = assembler.assemble(user, List.of(progress));

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.episodeTitle()).isEqualTo("Next episode");
            assertThat(item.seriesTitle()).isEqualTo("The Last of Us");
            assertThat(item.stillPath()).isEqualTo("/still.jpg");
            assertThat(item.runtimeMinutes()).isEqualTo(55);
            assertThat(item.watchedPercentage()).isEqualTo(50.0);
            assertThat(item.contentId()).isEqualTo(contentId);
            assertThat(item.communityAverageScore()).isEqualTo(8.5);
            assertThat(item.availableToWatch()).isFalse();
        });
    }
}
