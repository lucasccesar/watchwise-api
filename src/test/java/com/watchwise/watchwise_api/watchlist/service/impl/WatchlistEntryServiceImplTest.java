package com.watchwise.watchwise_api.watchlist.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.common.transaction.AdvisoryLock;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.contentreleasedatesnapshot.service.ContentReleaseDateSnapshotService;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistEntryCreationDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistEntryReorderDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistEntryResponseDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistPageResponseDTO;
import com.watchwise.watchwise_api.watchlist.entity.WatchlistEntry;
import com.watchwise.watchwise_api.watchlist.mapper.WatchlistEntryMapper;
import com.watchwise.watchwise_api.watchlist.repository.WatchlistEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WatchlistEntryServiceImplTest {

    @Mock private WatchlistEntryRepository watchlistEntryRepository;
    @Mock private UserRepository userRepository;
    @Mock private ContentRepository contentRepository;
    @Mock private ContentService contentService;
    @Mock private FollowerRepository followerRepository;
    @Mock private WatchlistEntryMapper watchlistEntryMapper;
    @Mock private AdvisoryLock advisoryLock;
    @Mock private ContentReleaseDateSnapshotService releaseDateSnapshotService;
    @Spy private PageRequestFactory pageRequestFactory = new PageRequestFactory();
    @InjectMocks private WatchlistEntryServiceImpl watchlistEntryService;

    private UUID userId;
    private User user;
    private Content movie;
    private Content series;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        user = User.builder().id(userId).isProfilePublic(true).preferredRegion("BR").build();
        movie = content("550", ContentType.MOVIE);
        series = content("1396", ContentType.SERIES);
    }

    @Test
    void shouldReturnMovieAndSeriesInGlobalOrderWithUpcomingCount() {
        WatchlistEntry movieEntry = entry(movie, ContentType.MOVIE, 1);
        WatchlistEntry seriesEntry = entry(series, ContentType.SERIES, 2);
        stubResolution(List.of(movieEntry, seriesEntry), Map.of(
                movieEntry.getId(), LocalDate.of(2027, 1, 1),
                seriesEntry.getId(), LocalDate.of(2026, 1, 1)));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(watchlistEntryRepository.findByUserIdOrderByPositionAsc(eq(userId), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(movieEntry, seriesEntry)));
        when(releaseDateSnapshotService.countUpcoming(userId, null, "BR", LocalDate.now())).thenReturn(1L);
        when(watchlistEntryMapper.watchlistEntryToResponseDto(movieEntry)).thenReturn(response(movieEntry));
        when(watchlistEntryMapper.watchlistEntryToResponseDto(seriesEntry)).thenReturn(response(seriesEntry));

        WatchlistPageResponseDTO result = watchlistEntryService.getWatchlist(userId, userId, null, 1, 10);

        assertThat(result.content()).extracting(WatchlistEntryResponseDTO::position).containsExactly(1, 2);
        assertThat(result.content()).extracting(WatchlistEntryResponseDTO::releaseDate)
                .containsExactly(LocalDate.of(2027, 1, 1), LocalDate.of(2026, 1, 1));
        assertThat(result.upcomingCount()).isEqualTo(1);
        verify(watchlistEntryRepository, never()).findByUserIdOrderByPositionAsc(userId);
    }

    @Test
    void shouldFilterByTypeWithoutRenumberingGlobalPositions() {
        WatchlistEntry movieEntry = entry(movie, ContentType.MOVIE, 1);
        WatchlistEntry seriesEntry = entry(series, ContentType.SERIES, 2);
        stubResolution(List.of(seriesEntry), Map.of(seriesEntry.getId(), LocalDate.of(2027, 2, 1)));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(watchlistEntryRepository.findByUserIdAndTypeOrderByPositionAsc(
                eq(userId), eq(ContentType.SERIES), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(seriesEntry)));
        when(releaseDateSnapshotService.countUpcoming(userId, ContentType.SERIES, "BR", LocalDate.now()))
                .thenReturn(1L);
        when(watchlistEntryMapper.watchlistEntryToResponseDto(seriesEntry)).thenReturn(response(seriesEntry));

        WatchlistPageResponseDTO result = watchlistEntryService.getWatchlist(
                userId, userId, ContentType.SERIES, 1, 10);

        assertThat(result.content()).singleElement().extracting(WatchlistEntryResponseDTO::position).isEqualTo(2);
        assertThat(result.totalElements()).isEqualTo(1);
    }

    @Test
    void shouldResolveAllSeriesWhenCountingGlobalUpcoming() {
        WatchlistEntry movieEntry = entry(movie, ContentType.MOVIE, 1);
        WatchlistEntry seriesEntryOutsidePage = entry(series, ContentType.SERIES, 2);
        List<WatchlistEntry> entriesForDates = List.of(movieEntry, seriesEntryOutsidePage);
        stubResolution(entriesForDates, Map.of(seriesEntryOutsidePage.getId(), LocalDate.of(2027, 3, 1)));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(watchlistEntryRepository.findByUserIdOrderByPositionAsc(eq(userId), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(movieEntry), PageRequest.of(0, 1), 2));
        when(watchlistEntryRepository.findByUserIdAndTypeOrderByPositionAsc(userId, ContentType.SERIES))
                .thenReturn(List.of(seriesEntryOutsidePage));
        when(releaseDateSnapshotService.countUpcoming(userId, null, "BR", LocalDate.now())).thenReturn(1L);
        when(watchlistEntryMapper.watchlistEntryToResponseDto(movieEntry)).thenReturn(response(movieEntry));

        WatchlistPageResponseDTO result = watchlistEntryService.getWatchlist(userId, userId, null, 1, 1);

        assertThat(result.upcomingCount()).isEqualTo(1);
        verify(releaseDateSnapshotService).resolve(user, entriesForDates);
    }

    @Test
    void shouldAppendNewEntryAfterBothTypes() {
        ContentRefDTO reference = new ContentRefDTO(
                UUID.randomUUID(), "550", ContentType.MOVIE, null, null, null, null, null,
                LocalDateTime.now(), LocalDateTime.now());
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class))).thenReturn(reference);
        when(watchlistEntryRepository.countByUserId(userId)).thenReturn(2L);
        when(userRepository.getReferenceById(userId)).thenReturn(user);
        when(contentRepository.getReferenceById(reference.id())).thenReturn(movie);
        when(watchlistEntryRepository.save(any(WatchlistEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(watchlistEntryMapper.watchlistEntryToResponseDto(any())).thenAnswer(invocation ->
                response(invocation.getArgument(0)));

        watchlistEntryService.insertEntry(userId, ContentType.MOVIE, new WatchlistEntryCreationDTO("550"));

        verify(advisoryLock).lock("watchlist|" + userId);
        verify(releaseDateSnapshotService).writeThrough(user, movie);
        verify(watchlistEntryRepository).flush();
        verify(watchlistEntryRepository).save(any(WatchlistEntry.class));
    }

    @Test
    void shouldShiftBothTypesWhenRemovingAnEntry() {
        WatchlistEntry seriesEntry = entry(series, ContentType.SERIES, 2);
        when(watchlistEntryRepository.findById(seriesEntry.getId())).thenReturn(Optional.of(seriesEntry));

        watchlistEntryService.removeEntry(userId, ContentType.SERIES, seriesEntry.getId());

        verify(watchlistEntryRepository).parkPositionsInRange(
                userId, 3, Integer.MAX_VALUE, WatchlistEntryServiceImpl.POSITION_PARK_OFFSET);
        verify(watchlistEntryRepository).settleParkedPositions(
                userId, WatchlistEntryServiceImpl.POSITION_PARK_OFFSET, -1);
    }

    @Test
    void shouldMoveAcrossTypesUsingGlobalCount() {
        WatchlistEntry seriesEntry = entry(series, ContentType.SERIES, 3);
        when(watchlistEntryRepository.findById(seriesEntry.getId())).thenReturn(Optional.of(seriesEntry));
        when(watchlistEntryRepository.countByUserId(userId)).thenReturn(4L);
        when(watchlistEntryRepository.save(any(WatchlistEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(watchlistEntryMapper.watchlistEntryToResponseDto(seriesEntry)).thenReturn(response(seriesEntry));

        watchlistEntryService.moveEntry(userId, ContentType.SERIES, seriesEntry.getId(), new WatchlistEntryReorderDTO(1));

        verify(watchlistEntryRepository).parkPositionsInRange(
                userId, 1, 2, WatchlistEntryServiceImpl.POSITION_PARK_OFFSET);
        verify(watchlistEntryRepository).settleParkedPositions(
                userId, WatchlistEntryServiceImpl.POSITION_PARK_OFFSET, 1);
    }

    @Test
    void shouldRejectPrivateWatchlistForNonFollower() {
        user.setIsProfilePublic(false);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        UUID viewerId = UUID.randomUUID();
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(
                viewerId, userId, FollowStatus.ACCEPTED)).thenReturn(false);

        assertThatThrownBy(() -> watchlistEntryService.getWatchlist(viewerId, userId, null, 1, 10))
                .isInstanceOf(ForbiddenException.class);
        verify(watchlistEntryRepository, never()).findByUserIdOrderByPositionAsc(userId);
    }

    @Test
    void shouldRejectUnsupportedType() {
        assertThatThrownBy(() -> watchlistEntryService.getWatchlist(userId, userId, ContentType.SEASON, 1, 10))
                .isInstanceOf(BadRequestException.class);
    }

    private void stubResolution(List<WatchlistEntry> entries, Map<UUID, LocalDate> dates) {
        when(releaseDateSnapshotService.resolve(user, entries))
                .thenReturn(new ContentReleaseDateSnapshotService.WatchlistDateResolution(dates));
    }

    private WatchlistEntryResponseDTO response(WatchlistEntry entry) {
        return new WatchlistEntryResponseDTO(
                entry.getId(), entry.getType(), null, entry.getPosition(), entry.getCreatedAt(), entry.getUpdatedAt());
    }

    private WatchlistEntry entry(Content content, ContentType type, int position) {
        return WatchlistEntry.builder()
                .id(UUID.randomUUID()).user(user).content(content).type(type).position(position)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    private Content content(String tmdbId, ContentType type) {
        return Content.builder().id(UUID.randomUUID()).tmdbId(tmdbId).type(type)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }
}
