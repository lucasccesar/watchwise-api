package com.watchwise.watchwise_api.calendar.service.impl;

import com.watchwise.watchwise_api.calendar.dto.CalendarSource;
import com.watchwise.watchwise_api.calendar.service.CalendarInterest;
import com.watchwise.watchwise_api.calendar.service.CalendarInterestReader;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleKey;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.watchlist.entity.WatchlistEntry;
import com.watchwise.watchwise_api.watchlist.repository.WatchlistEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalendarInterestReaderImplTest {

    @Mock
    private WatchlistEntryRepository watchlistEntryRepository;

    @Mock
    private DiaryEntryRepository diaryEntryRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CalendarInterestReaderImpl reader;

    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        user = User.builder()
                .id(userId)
                .username("lucas")
                .email("lucas@email.com")
                .password("hashed_password")
                .isProfilePublic(true)
                .preferredLanguage("pt-BR")
                .preferredRegion("BR")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        when(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user));
    }

    @Test
    void shouldMergeAndDeduplicateInterestSourcesWithThePreferredLocale() {
        when(watchlistEntryRepository.findByUserIdAndTypeOrderByPositionAsc(userId, ContentType.MOVIE))
                .thenReturn(List.of(
                        buildWatchlistEntry("550", ContentType.MOVIE, 1),
                        buildWatchlistEntry("550", ContentType.MOVIE, 2)));
        when(watchlistEntryRepository.findByUserIdAndTypeOrderByPositionAsc(userId, ContentType.SERIES))
                .thenReturn(List.of(
                        buildWatchlistEntry("1396", ContentType.SERIES, 1),
                        buildWatchlistEntry("1396", ContentType.SERIES, 2)));
        when(diaryEntryRepository.findDistinctInProgressSeriesTmdbIdsByUserId(userId))
                .thenReturn(List.of("1396", "119051", "119051"));

        CalendarInterest result = reader.read(userId);

        assertThat(result.movieTmdbIds()).containsExactly("550");
        assertThat(result.seriesTmdbIds()).containsExactly("1396");
        assertThat(result.inProgressSeriesTmdbIds()).containsExactly("119051", "1396");
        assertThat(result.preferredLanguage()).isEqualTo("pt-BR");
        assertThat(result.preferredRegion()).isEqualTo("BR");
        assertThat(result.sourcesByKey()).containsExactlyInAnyOrderEntriesOf(Map.of(
                new CalendarScheduleKey(ContentType.MOVIE, "550", "pt-BR", "BR"), Set.of(CalendarSource.WATCHLIST),
                new CalendarScheduleKey(ContentType.SERIES, "1396", "pt-BR", "BR"),
                Set.of(CalendarSource.WATCHLIST, CalendarSource.IN_PROGRESS),
                new CalendarScheduleKey(ContentType.SERIES, "119051", "pt-BR", "BR"),
                Set.of(CalendarSource.IN_PROGRESS)));
    }

    @Test
    void shouldReturnImmutableCollections() {
        when(watchlistEntryRepository.findByUserIdAndTypeOrderByPositionAsc(userId, ContentType.MOVIE))
                .thenReturn(List.of(buildWatchlistEntry("550", ContentType.MOVIE, 1)));
        when(watchlistEntryRepository.findByUserIdAndTypeOrderByPositionAsc(userId, ContentType.SERIES))
                .thenReturn(List.of());
        when(diaryEntryRepository.findDistinctInProgressSeriesTmdbIdsByUserId(userId)).thenReturn(List.of());

        CalendarInterest result = reader.read(userId);
        CalendarScheduleKey movieKey = new CalendarScheduleKey(ContentType.MOVIE, "550", "pt-BR", "BR");

        assertThatThrownBy(() -> result.movieTmdbIds().add("680"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.seriesTmdbIds().add("1396"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.inProgressSeriesTmdbIds().add("119051"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.sourcesByKey().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.sourcesByKey().get(movieKey).add(CalendarSource.IN_PROGRESS))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private WatchlistEntry buildWatchlistEntry(String tmdbId, ContentType type, int position) {
        LocalDateTime now = LocalDateTime.now();
        Content content = Content.builder()
                .tmdbId(tmdbId)
                .type(type)
                .createdAt(now)
                .updatedAt(now)
                .build();
        return WatchlistEntry.builder()
                .user(user)
                .content(content)
                .type(type)
                .position(position)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
