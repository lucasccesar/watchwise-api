package com.watchwise.watchwise_api.diaryentry.mapper;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryResponseDTO;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DiaryEntryMapperTest {

    private DiaryEntryMapper diaryEntryMapper;

    @BeforeEach
    void setUp() {
        DiaryEntryMapperImpl mapper = new DiaryEntryMapperImpl();
        ReflectionTestUtils.setField(mapper, "contentMapper", Mappers.getMapper(ContentMapper.class));
        diaryEntryMapper = mapper;
    }

    @Test
    @DisplayName("[diaryEntryToResponseDto] Should Map All Fields Including WatchNumber - When Mapping DiaryEntry To DiaryEntryResponseDTO")
    void shouldMapAllFieldsIncludingWatchNumberWhenMappingDiaryEntryToDiaryEntryResponseDto() {
        UUID userId = UUID.randomUUID();
        User user = User.builder().id(userId).build();

        Content content = Content.builder()
                .id(UUID.randomUUID())
                .tmdbId("550")
                .type(ContentType.MOVIE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        LocalDate watchedDate = LocalDate.of(2024, 3, 15);
        LocalDateTime createdAt = LocalDateTime.now().minusDays(1);
        LocalDateTime updatedAt = LocalDateTime.now();

        DiaryEntry entry = DiaryEntry.builder()
                .id(UUID.randomUUID())
                .user(user)
                .content(content)
                .comment("Great movie")
                .score(9)
                .watchedDate(watchedDate)
                .watchNumber(2)
                .watchedInTheater(true)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .likesCount(5)
                .build();

        DiaryEntryResponseDTO result = diaryEntryMapper.diaryEntryToResponseDto(entry, true);

        assertThat(result.id()).isEqualTo(entry.getId());
        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.content().id()).isEqualTo(content.getId());
        assertThat(result.comment()).isEqualTo("Great movie");
        assertThat(result.score()).isEqualTo(9);
        assertThat(result.watchedDate()).isEqualTo(watchedDate);
        assertThat(result.watchNumber()).isEqualTo(2);
        assertThat(result.watchedInTheater()).isTrue();
        assertThat(result.customPosterUrl()).isNull();
        assertThat(result.createdAt()).isEqualTo(createdAt);
        assertThat(result.updatedAt()).isEqualTo(updatedAt);
        assertThat(result.likesCount()).isEqualTo(5);
        assertThat(result.likedByMe()).isTrue();
        assertThat(result.commentsCount()).isZero();

        DiaryEntryResponseDTO enriched = result.withCommentsCount(4)
                .withCustomPosterUrl("https://image.tmdb.org/t/p/w342/custom.png");
        assertThat(enriched.commentsCount()).isEqualTo(4);
        assertThat(enriched.customPosterUrl()).isEqualTo("https://image.tmdb.org/t/p/w342/custom.png");
    }

    @Test
    @DisplayName("[diaryEntryToResponseDto] Should Map WatchNumber One - When Entry Is A First Watch")
    void shouldMapWatchNumberOneWhenEntryIsAFirstWatch() {
        User user = User.builder().id(UUID.randomUUID()).build();
        Content content = Content.builder()
                .id(UUID.randomUUID())
                .tmdbId("550")
                .type(ContentType.MOVIE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        DiaryEntry entry = DiaryEntry.builder()
                .id(UUID.randomUUID())
                .user(user)
                .content(content)
                .watchNumber(1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        DiaryEntryResponseDTO result = diaryEntryMapper.diaryEntryToResponseDto(entry, false);

        assertThat(result.watchNumber()).isEqualTo(1);
        assertThat(result.likedByMe()).isFalse();
    }

    @Test
    @DisplayName("[diaryEntryToResponseDto] Should Map Episode Event Coordinates And Visibility Flag - When Mapping A Bulk Watch Event")
    void shouldMapEpisodeEventCoordinatesAndIgnoreFlagWhenMappingABulkWatchEvent() {
        User user = User.builder().id(UUID.randomUUID()).build();
        Content episode = Content.builder()
                .id(UUID.randomUUID())
                .type(ContentType.EPISODE)
                .seriesTmdbId("1399")
                .seasonNumber(2)
                .episodeNumber(3)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        DiaryEntry entry = DiaryEntry.builder()
                .id(UUID.randomUUID())
                .user(user)
                .content(episode)
                .watchNumber(2)
                .ignore(false)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        DiaryEntryResponseDTO result = diaryEntryMapper.diaryEntryToResponseDto(entry, false);

        assertThat(result.content().type()).isEqualTo(ContentType.EPISODE);
        assertThat(result.content().seriesTmdbId()).isEqualTo("1399");
        assertThat(result.content().seasonNumber()).isEqualTo(2);
        assertThat(result.content().episodeNumber()).isEqualTo(3);
        assertThat(result.watchNumber()).isEqualTo(2);
        assertThat(result.ignore()).isFalse();
    }
}
