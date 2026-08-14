package com.watchwise.watchwise_api.content.mapper;

import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ContentMapperTest {

    private ContentMapper contentMapper;

    @BeforeEach
    void setUp() {
        contentMapper = Mappers.getMapper(ContentMapper.class);
    }

    @Test
    @DisplayName("[contentRefCreationDtoToContent] Should Map All Fields - When Type Is Movie")
    void shouldMapAllFieldsWhenTypeIsMovie() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("550", ContentType.MOVIE, null, null, null, null, null);

        Content result = contentMapper.contentRefCreationDtoToContent(dto);

        assertThat(result.getTmdbId()).isEqualTo("550");
        assertThat(result.getType()).isEqualTo(ContentType.MOVIE);
        assertThat(result.getSeriesTmdbId()).isNull();
        assertThat(result.getSeasonNumber()).isNull();
        assertThat(result.getEpisodeNumber()).isNull();
    }

    @Test
    @DisplayName("[contentRefCreationDtoToContent] Should Map All Fields - When Type Is Episode")
    void shouldMapAllFieldsWhenTypeIsEpisode() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.EPISODE, "1399", 1, 3, null, null);

        Content result = contentMapper.contentRefCreationDtoToContent(dto);

        assertThat(result.getTmdbId()).isNull();
        assertThat(result.getType()).isEqualTo(ContentType.EPISODE);
        assertThat(result.getSeriesTmdbId()).isEqualTo("1399");
        assertThat(result.getSeasonNumber()).isEqualTo(1);
        assertThat(result.getEpisodeNumber()).isEqualTo(3);
    }

    @Test
    @DisplayName("[contentRefCreationDtoToContent] Should Ignore Id, CreatedAt And UpdatedAt - When Mapping ContentRefCreationDTO To Content")
    void shouldIgnoreIdCreatedAtAndUpdatedAtWhenMappingContentRefCreationDtoToContent() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("550", ContentType.MOVIE, null, null, null, null, null);

        Content result = contentMapper.contentRefCreationDtoToContent(dto);

        assertThat(result.getId()).isNull();
        assertThat(result.getCreatedAt()).isNull();
        assertThat(result.getUpdatedAt()).isNull();
    }

    @Test
    @DisplayName("[contentToContentRefDto] Should Map All Fields - When Mapping Content To ContentRefDTO")
    void shouldMapAllFieldsWhenMappingContentToContentRefDto() {
        UUID id = UUID.randomUUID();
        LocalDateTime createdAt = LocalDateTime.now().minusDays(1);
        LocalDateTime updatedAt = LocalDateTime.now();

        Content content = Content.builder()
                .id(id)
                .tmdbId(null)
                .type(ContentType.SEASON)
                .seriesTmdbId("1399")
                .seasonNumber(1)
                .episodeNumber(null)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();

        ContentRefDTO result = contentMapper.contentToContentRefDto(content);

        assertThat(result.id()).isEqualTo(id);
        assertThat(result.tmdbId()).isNull();
        assertThat(result.type()).isEqualTo(ContentType.SEASON);
        assertThat(result.seriesTmdbId()).isEqualTo("1399");
        assertThat(result.seasonNumber()).isEqualTo(1);
        assertThat(result.episodeNumber()).isNull();
        assertThat(result.createdAt()).isEqualTo(createdAt);
        assertThat(result.updatedAt()).isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("[contentRefCreationDtoToContent] Should Map IsSeasonFinale And IsSeriesFinale - When Present")
    void shouldMapIsSeasonFinaleAndIsSeriesFinaleWhenPresent() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.EPISODE, "1399", 1, 3, true, false);

        Content result = contentMapper.contentRefCreationDtoToContent(dto);

        assertThat(result.getIsSeasonFinale()).isTrue();
        assertThat(result.getIsSeriesFinale()).isFalse();
    }

    @Test
    @DisplayName("[contentToContentRefDto] Should Map IsSeasonFinale And IsSeriesFinale - When Present")
    void shouldMapIsSeasonFinaleAndIsSeriesFinaleToContentRefDtoWhenPresent() {
        Content content = Content.builder()
                .id(UUID.randomUUID())
                .type(ContentType.EPISODE)
                .seriesTmdbId("1399")
                .seasonNumber(1)
                .episodeNumber(3)
                .isSeasonFinale(true)
                .isSeriesFinale(false)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        ContentRefDTO result = contentMapper.contentToContentRefDto(content);

        assertThat(result.isSeasonFinale()).isTrue();
        assertThat(result.isSeriesFinale()).isFalse();
    }
}
