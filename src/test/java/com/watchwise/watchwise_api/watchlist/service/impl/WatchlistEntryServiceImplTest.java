package com.watchwise.watchwise_api.watchlist.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.common.transaction.AdvisoryLock;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardStatsDTO;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
import com.watchwise.watchwise_api.content.service.ContentCardSpec;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.content.service.impl.ContentCardAssembler;
import com.watchwise.watchwise_api.contentreleasedatesnapshot.service.ContentReleaseDateSnapshotService;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistEntryCreationDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistEntryReorderDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistEntryResponseDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistPageResponseDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistAggregateDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistCardDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistSort;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistStatus;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistViewResponseDTO;
import com.watchwise.watchwise_api.watchlist.entity.WatchlistEntry;
import com.watchwise.watchwise_api.watchlist.mapper.WatchlistEntryMapper;
import com.watchwise.watchwise_api.watchlist.repository.WatchlistEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.hibernate.exception.ConstraintViolationException;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import com.watchwise.watchwise_api.seriesprogress.repository.SeriesProgressReadRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    @Mock private ContentCardAssembler contentCardAssembler;
    @Mock private SeriesProgressReadRepository seriesProgressReadRepository;
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
        lenient().when(releaseDateSnapshotService.resolve(any(User.class), anyList()))
                .thenReturn(new ContentReleaseDateSnapshotService.WatchlistDateResolution(Map.of()));
        lenient().when(releaseDateSnapshotService.countUpcoming(
                        any(UUID.class), nullable(ContentType.class), nullable(String.class), any(LocalDate.class)))
                .thenReturn(0L);
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

    @Test
    void shouldRejectMissingUserBeforeLoadingWatchlist() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> watchlistEntryService.getWatchlist(userId, userId, ContentType.MOVIE, 1, 10))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");
        verifyNoInteractions(watchlistEntryRepository);
    }

    @Test
    void shouldAllowAcceptedFollowerToViewPrivateWatchlist() {
        user.setIsProfilePublic(false);
        UUID viewerId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(
                viewerId, userId, FollowStatus.ACCEPTED)).thenReturn(true);
        when(watchlistEntryRepository.findByUserIdAndTypeOrderByPositionAsc(
                eq(userId), eq(ContentType.MOVIE), any(PageRequest.class))).thenReturn(Page.empty());

        WatchlistPageResponseDTO result = watchlistEntryService.getWatchlist(
                viewerId, userId, ContentType.MOVIE, 1, 10);

        assertThat(result.content()).isEmpty();
        verify(followerRepository).existsByFollowerIdAndFollowedIdAndStatus(
                viewerId, userId, FollowStatus.ACCEPTED);
    }

    @Test
    void shouldApplyOneBasedPageNumberAndProvidedSize() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(watchlistEntryRepository.findByUserIdAndTypeOrderByPositionAsc(
                eq(userId), eq(ContentType.MOVIE), any(PageRequest.class))).thenReturn(Page.empty());

        watchlistEntryService.getWatchlist(userId, userId, ContentType.MOVIE, 3, 25);

        verify(watchlistEntryRepository).findByUserIdAndTypeOrderByPositionAsc(
                eq(userId), eq(ContentType.MOVIE), eq(PageRequest.of(2, 25)));
    }

    @Test
    void shouldClampPageSizeToConfiguredMaximum() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(watchlistEntryRepository.findByUserIdAndTypeOrderByPositionAsc(
                eq(userId), eq(ContentType.MOVIE), any(PageRequest.class))).thenReturn(Page.empty());

        watchlistEntryService.getWatchlist(userId, userId, ContentType.MOVIE, 1, 1001);

        verify(watchlistEntryRepository).findByUserIdAndTypeOrderByPositionAsc(
                eq(userId), eq(ContentType.MOVIE), eq(PageRequest.of(0, 1000)));
    }

    @Test
    void shouldRejectInvalidPaginationValuesBeforeQuerying() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> watchlistEntryService.getWatchlist(
                userId, userId, ContentType.MOVIE, -1, 10)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> watchlistEntryService.getWatchlist(
                userId, userId, ContentType.MOVIE, 1, 0)).isInstanceOf(BadRequestException.class);
        verify(watchlistEntryRepository, never()).findByUserIdAndTypeOrderByPositionAsc(
                any(UUID.class), any(ContentType.class), any(PageRequest.class));
    }

    @Test
    void shouldFilterByTypeAndGenreBeforePaginationAndAggregateFilteredCandidates() {
        WatchlistEntry dramaMovie = entry(content("550", ContentType.MOVIE, 120, List.of("Drama")), ContentType.MOVIE, 1);
        WatchlistEntry dramaSeries = entry(content("1396", ContentType.SERIES, null, List.of("Drama"), 480), ContentType.SERIES, 2);
        WatchlistEntry comedyMovie = entry(content("680", ContentType.MOVIE, 90, List.of("Comedy")), ContentType.MOVIE, 3);
        List<WatchlistEntry> candidates = List.of(dramaMovie, dramaSeries, comedyMovie);
        stubViewCandidates(candidates);
        stubViewDates(List.of(dramaMovie), Map.of(
                dramaMovie.getId(), LocalDate.now().minusDays(2)));
        stubSeriesProgress(candidates);
        stubCards(candidates);

        WatchlistViewResponseDTO result = watchlistEntryService.getWatchlistView(
                userId, userId, ContentType.MOVIE, "Drama", WatchlistStatus.ALL,
                WatchlistSort.DATE_ADDED, "ASC", 1, 1);

        assertThat(result.content()).extracting(card -> card.card().tmdbId()).containsExactly("550");
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(1);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.hasNext()).isFalse();
        assertThat(result.aggregate()).isEqualTo(new WatchlistAggregateDTO(1, 1, 0, 120, 0));
    }

    @Test
    void shouldCountUpcomingAfterFilteringBeforePagination() {
        WatchlistEntry upcoming = entry(content("550", ContentType.MOVIE, 120, List.of("Drama")), ContentType.MOVIE, 1);
        WatchlistEntry released = entry(content("680", ContentType.MOVIE, 90, List.of("Drama")), ContentType.MOVIE, 2);
        List<WatchlistEntry> candidates = List.of(upcoming, released);
        stubViewCandidates(candidates);
        stubViewDates(candidates, Map.of(
                upcoming.getId(), LocalDate.now().plusDays(5),
                released.getId(), LocalDate.now().minusDays(5)));
        stubSeriesProgress(candidates);
        stubCards(candidates);

        WatchlistViewResponseDTO result = watchlistEntryService.getWatchlistView(
                userId, userId, null, null, WatchlistStatus.UPCOMING,
                WatchlistSort.DATE_ADDED, "ASC", 1, 1);

        assertThat(result.content()).extracting(card -> card.card().tmdbId()).containsExactly("550");
        assertThat(result.aggregate()).isEqualTo(new WatchlistAggregateDTO(1, 1, 0, 120, 1));
    }

    @Test
    void shouldFilterInProgressSeriesUsingEpisodeDiaryProgress() {
        WatchlistEntry inProgress = entry(content("1396", ContentType.SERIES, null, List.of("Drama"), 480), ContentType.SERIES, 1);
        WatchlistEntry newSeries = entry(content("1399", ContentType.SERIES, null, List.of("Drama"), 360), ContentType.SERIES, 2);
        List<WatchlistEntry> candidates = List.of(inProgress, newSeries);
        stubViewCandidates(candidates);
        stubViewDates(candidates, Map.of(
                inProgress.getId(), LocalDate.now().minusDays(5),
                newSeries.getId(), LocalDate.now().minusDays(4)));
        SeriesProgressReadRepository.SeriesProgressCandidate progress = org.mockito.Mockito.mock(
                SeriesProgressReadRepository.SeriesProgressCandidate.class);
        when(progress.getSeriesTmdbId()).thenReturn("1396");
        when(seriesProgressReadRepository.findProgressByUserIdAndSeriesTmdbIds(
                eq(userId), anyCollection())).thenReturn(List.of(progress));
        stubCards(candidates);

        WatchlistViewResponseDTO result = watchlistEntryService.getWatchlistView(
                userId, userId, null, null, WatchlistStatus.IN_PROGRESS,
                WatchlistSort.DATE_ADDED, "ASC", 1, 10);

        assertThat(result.content()).extracting(card -> card.card().tmdbId()).containsExactly("1396");
        assertThat(result.content()).singleElement().extracting(WatchlistCardDTO::status)
                .isEqualTo(WatchlistStatus.IN_PROGRESS);
        verify(seriesProgressReadRepository).findProgressByUserIdAndSeriesTmdbIds(
                eq(userId), org.mockito.ArgumentMatchers.argThat(ids ->
                        ids.size() == 2 && ids.containsAll(List.of("1396", "1399"))));
    }

    @Test
    void shouldApplyAllVisualSortsAndStableTieBreaking() {
        WatchlistEntry first = entry(content("1", ContentType.MOVIE, 100, List.of("Drama")), ContentType.MOVIE, 2);
        WatchlistEntry second = entry(content("2", ContentType.MOVIE, 200, List.of("Drama")), ContentType.MOVIE, 1);
        WatchlistEntry third = entry(content("3", ContentType.MOVIE, 150, List.of("Drama")), ContentType.MOVIE, 3);
        LocalDateTime addedAt = LocalDateTime.of(2026, 1, 1, 12, 0);
        first.setCreatedAt(addedAt);
        second.setCreatedAt(addedAt.plusDays(1));
        third.setCreatedAt(addedAt.plusDays(2));
        List<WatchlistEntry> candidates = List.of(first, second, third);
        stubViewCandidates(candidates);
        stubViewDates(candidates, Map.of(
                first.getId(), LocalDate.of(2026, 1, 1),
                second.getId(), LocalDate.of(2027, 1, 1),
                third.getId(), LocalDate.of(2026, 6, 1)));
        stubSeriesProgress(candidates);
        stubCards(candidates);

        assertThat(viewIds(WatchlistSort.DATE_ADDED, "ASC")).containsExactly("1", "2", "3");
        assertThat(viewIds(WatchlistSort.RELEASE_DATE, "DESC")).containsExactly("2", "3", "1");
        assertThat(viewIds(WatchlistSort.RUNTIME, "DESC")).containsExactly("2", "3", "1");
        assertThat(viewIds(WatchlistSort.RATING, "DESC")).containsExactly("2", "1", "3");
        assertThat(viewIds(WatchlistSort.TITLE, "ASC")).containsExactly("1", "2", "3");
    }

    @Test
    void shouldUseWatchlistEntryIdAsStableTieBreaker() {
        WatchlistEntry higherId = entry(content("higher", ContentType.MOVIE, 100, List.of("Drama")), ContentType.MOVIE, 1);
        WatchlistEntry lowerId = entry(content("lower", ContentType.MOVIE, 100, List.of("Drama")), ContentType.MOVIE, 2);
        lowerId.setId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        higherId.setId(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        List<WatchlistEntry> candidates = List.of(higherId, lowerId);
        stubViewCandidates(candidates);
        LocalDate sameReleaseDate = LocalDate.of(2026, 1, 1);
        stubViewDates(candidates, Map.of(
                higherId.getId(), sameReleaseDate,
                lowerId.getId(), sameReleaseDate));
        stubSeriesProgress(candidates);
        stubCards(candidates);

        assertThat(viewIds(WatchlistSort.RUNTIME, "ASC")).containsExactly("lower", "higher");
    }

    @Test
    void shouldResolveCanonicalRuntimeForSortingAndAggregateWhenPersistedRuntimeIsMissing() {
        WatchlistEntry missingRuntime = entry(
                content("550", ContentType.MOVIE, null, List.of("Drama")), ContentType.MOVIE, 1);
        WatchlistEntry knownRuntime = entry(
                content("680", ContentType.MOVIE, 90, List.of("Drama")), ContentType.MOVIE, 2);
        List<WatchlistEntry> candidates = List.of(missingRuntime, knownRuntime);
        stubViewCandidates(candidates);
        stubViewDates(candidates, Map.of());
        stubSeriesProgress(candidates);
        ContentCardDTO canonicalMissingRuntime = new ContentCardDTO(
                missingRuntime.getContent().getId(), ContentType.MOVIE, "550", null, null, null,
                "Fight Club", "/fight-club.jpg", null, LocalDate.of(1999, 10, 15), 1999,
                139, null, null, null, List.of("Drama"), null, null, ContentPreviewStatus.AVAILABLE);
        ContentCardDTO knownRuntimeCard = card(
                knownRuntime.getContent(), "680", "Heat", ContentPreviewStatus.AVAILABLE, 8.0);
        when(contentCardAssembler.assemble(anyCollection(), any(ContentCardContext.class), anySet()))
                .thenReturn(Map.of(
                        ContentCoordinate.from(missingRuntime.getContent()), canonicalMissingRuntime,
                        ContentCoordinate.from(knownRuntime.getContent()), knownRuntimeCard));

        WatchlistViewResponseDTO result = watchlistEntryService.getWatchlistView(
                userId, userId, null, null, WatchlistStatus.ALL,
                WatchlistSort.RUNTIME, "DESC", 1, 10);

        assertThat(result.content()).extracting(card -> card.card().tmdbId())
                .containsExactly("550", "680");
        assertThat(result.aggregate().totalRuntimeMinutes()).isEqualTo(229L);
        verify(contentCardAssembler, times(2)).assemble(anyCollection(), any(ContentCardContext.class), anySet());
    }

    @Test
    void shouldKeepUnknownSeriesTotalRuntimeOutOfSortAndAggregateWhenCanonicalAverageExists() {
        WatchlistEntry seriesEntry = entry(
                content("1396", ContentType.SERIES, null, List.of("Drama"), null), ContentType.SERIES, 1);
        WatchlistEntry movieEntry = entry(
                content("550", ContentType.MOVIE, 60, List.of("Drama")), ContentType.MOVIE, 2);
        List<WatchlistEntry> candidates = List.of(seriesEntry, movieEntry);
        stubViewCandidates(candidates);
        stubViewDates(candidates, Map.of());
        stubSeriesProgress(candidates);
        ContentCardDTO seriesCard = new ContentCardDTO(
                seriesEntry.getContent().getId(), ContentType.SERIES, "1396", null, null, null,
                "Breaking Bad", "/breaking-bad.jpg", null, LocalDate.of(2008, 1, 20), 2008,
                47, null, 5, null, List.of("Drama"), null, null, ContentPreviewStatus.AVAILABLE);
        ContentCardDTO movieCard = card(
                movieEntry.getContent(), "550", "Fight Club", ContentPreviewStatus.AVAILABLE, 8.0);
        when(contentCardAssembler.assemble(anyCollection(), any(ContentCardContext.class), anySet()))
                .thenReturn(Map.of(
                        ContentCoordinate.from(seriesEntry.getContent()), seriesCard,
                        ContentCoordinate.from(movieEntry.getContent()), movieCard));

        WatchlistViewResponseDTO result = watchlistEntryService.getWatchlistView(
                userId, userId, null, null, WatchlistStatus.ALL,
                WatchlistSort.RUNTIME, "DESC", 1, 10);

        assertThat(result.content()).extracting(card -> card.card().tmdbId())
                .containsExactly("550", "1396");
        assertThat(result.aggregate().totalRuntimeMinutes()).isEqualTo(60L);
        assertThat(result.content().get(1).card().runtimeMinutes()).isEqualTo(47);
    }

    @Test
    void shouldRejectMaterializedSortWhenFilteredCandidatesExceedConfiguredCap() {
        org.springframework.test.util.ReflectionTestUtils.setField(
                watchlistEntryService, "maxMaterializedSortCandidates", 1);
        WatchlistEntry first = entry(content("1", ContentType.MOVIE, 100, List.of("Drama")), ContentType.MOVIE, 1);
        WatchlistEntry second = entry(content("2", ContentType.MOVIE, 100, List.of("Drama")), ContentType.MOVIE, 2);
        List<WatchlistEntry> candidates = List.of(first, second);
        stubViewCandidates(candidates);
        stubSeriesProgress(candidates);

        assertThatThrownBy(() -> watchlistEntryService.getWatchlistView(
                userId, userId, null, null, WatchlistStatus.ALL,
                WatchlistSort.TITLE, "ASC", 1, 10))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("restrict type, genre or status");
        assertThatThrownBy(() -> watchlistEntryService.getWatchlistView(
                userId, userId, null, null, WatchlistStatus.ALL,
                WatchlistSort.RATING, "ASC", 1, 10))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(releaseDateSnapshotService, contentCardAssembler, seriesProgressReadRepository);
    }

    @Test
    void shouldRejectPrivateWatchlistViewBeforeLoadingCandidates() {
        user.setIsProfilePublic(false);
        UUID viewerId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(
                viewerId, userId, FollowStatus.ACCEPTED)).thenReturn(false);

        assertThatThrownBy(() -> watchlistEntryService.getWatchlistView(
                viewerId, userId, null, null, WatchlistStatus.ALL,
                WatchlistSort.DATE_ADDED, "DESC", 1, 10))
                .isInstanceOf(ForbiddenException.class);
        verify(watchlistEntryRepository, never()).findByUserIdWithContentForView(userId);
    }

    @Test
    void shouldRejectBlankGenreBeforeLoadingCandidates() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> watchlistEntryService.getWatchlistView(
                userId, userId, null, "  ", WatchlistStatus.ALL,
                WatchlistSort.DATE_ADDED, "DESC", 1, 10))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("genre must not be blank");
        verify(watchlistEntryRepository, never()).findByUserIdWithContentForView(userId);
    }

    @Test
    void shouldReadWatchlistViewWithoutCreatingContentAndPreservePartialCardStatus() {
        WatchlistEntry candidate = entry(content("550", ContentType.MOVIE, 120, List.of("Drama")), ContentType.MOVIE, 1);
        stubViewCandidates(List.of(candidate));
        stubViewDates(List.of(candidate), Map.of(candidate.getId(), LocalDate.now().minusDays(1)));
        stubSeriesProgress(List.of(candidate));
        ContentCardDTO partial = card(candidate.getContent(), "550", null, ContentPreviewStatus.PARTIAL, null);
        when(contentCardAssembler.assemble(anyCollection(), any(ContentCardContext.class), anySet()))
                .thenReturn(Map.of(ContentCoordinate.from(candidate.getContent()), partial));

        WatchlistViewResponseDTO result = watchlistEntryService.getWatchlistView(
                userId, userId, null, null, WatchlistStatus.ALL,
                WatchlistSort.DATE_ADDED, "DESC", 1, 10);

        assertThat(result.content()).singleElement().extracting(card -> card.card().previewStatus())
                .isEqualTo(ContentPreviewStatus.PARTIAL);
        verifyNoInteractions(contentService, contentRepository);
        verify(contentCardAssembler).assemble(anyCollection(), eq(new ContentCardContext(
                user.getPreferredLanguage(), user.getPreferredRegion(), userId, null)), anySet());
    }

    @Test
    void shouldInsertAtPositionOneWhenWatchlistIsEmpty() {
        stubContentReference(movie);
        when(watchlistEntryRepository.countByUserId(userId)).thenReturn(0L);
        when(userRepository.getReferenceById(userId)).thenReturn(user);
        when(contentRepository.getReferenceById(movie.getId())).thenReturn(movie);
        when(watchlistEntryRepository.save(any(WatchlistEntry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(watchlistEntryMapper.watchlistEntryToResponseDto(any(WatchlistEntry.class)))
                .thenAnswer(invocation -> response(invocation.getArgument(0)));

        watchlistEntryService.insertEntry(
                userId, ContentType.MOVIE, new WatchlistEntryCreationDTO(movie.getTmdbId()));

        var captor = org.mockito.ArgumentCaptor.forClass(WatchlistEntry.class);
        verify(watchlistEntryRepository).save(captor.capture());
        assertThat(captor.getValue().getPosition()).isEqualTo(1);
        verify(watchlistEntryRepository).flush();
    }

    @Test
    void shouldInsertAfterAllEntriesAcrossBothTypes() {
        stubContentReference(movie);
        when(watchlistEntryRepository.countByUserId(userId)).thenReturn(2L);
        when(userRepository.getReferenceById(userId)).thenReturn(user);
        when(contentRepository.getReferenceById(movie.getId())).thenReturn(movie);
        when(watchlistEntryRepository.save(any(WatchlistEntry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(watchlistEntryMapper.watchlistEntryToResponseDto(any(WatchlistEntry.class)))
                .thenAnswer(invocation -> response(invocation.getArgument(0)));

        watchlistEntryService.insertEntry(
                userId, ContentType.MOVIE, new WatchlistEntryCreationDTO(movie.getTmdbId()));

        var captor = org.mockito.ArgumentCaptor.forClass(WatchlistEntry.class);
        verify(watchlistEntryRepository).save(captor.capture());
        assertThat(captor.getValue().getPosition()).isEqualTo(3);
    }

    @Test
    void shouldMapKnownInsertConstraintToSpecificConflict() {
        stubContentReference(movie);
        when(watchlistEntryRepository.countByUserId(userId)).thenReturn(0L);
        when(userRepository.getReferenceById(userId)).thenReturn(user);
        when(contentRepository.getReferenceById(movie.getId())).thenReturn(movie);
        when(watchlistEntryRepository.save(any(WatchlistEntry.class))).thenThrow(
                dataIntegrityViolation("uq_watchlist_entries_user_id_type_content_id"));

        assertThatThrownBy(() -> watchlistEntryService.insertEntry(
                userId, ContentType.MOVIE, new WatchlistEntryCreationDTO(movie.getTmdbId())))
                .isInstanceOf(ConflictException.class)
                .hasMessage("This content is already in your watchlist");
    }

    @Test
    void shouldMapUnknownInsertConstraintToGenericConflict() {
        stubContentReference(movie);
        when(watchlistEntryRepository.countByUserId(userId)).thenReturn(0L);
        when(userRepository.getReferenceById(userId)).thenReturn(user);
        when(contentRepository.getReferenceById(movie.getId())).thenReturn(movie);
        when(watchlistEntryRepository.save(any(WatchlistEntry.class))).thenThrow(
                dataIntegrityViolation("uq_unknown"));

        assertThatThrownBy(() -> watchlistEntryService.insertEntry(
                userId, ContentType.MOVIE, new WatchlistEntryCreationDTO(movie.getTmdbId())))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Unable to insert this content into your watchlist");
    }

    @Test
    void shouldRejectUnsupportedMutationTypes() {
        WatchlistEntryCreationDTO request = new WatchlistEntryCreationDTO(movie.getTmdbId());

        assertThatThrownBy(() -> watchlistEntryService.insertEntry(userId, ContentType.SEASON, request))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> watchlistEntryService.removeEntry(
                userId, ContentType.EPISODE, UUID.randomUUID())).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> watchlistEntryService.moveEntry(
                userId, ContentType.SEASON, UUID.randomUUID(), new WatchlistEntryReorderDTO(1)))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(watchlistEntryRepository, contentService, contentRepository);
    }

    @Test
    void shouldRejectRemovingEntryOwnedByAnotherUser() {
        User otherUser = User.builder().id(UUID.randomUUID()).isProfilePublic(true).build();
        WatchlistEntry otherEntry = WatchlistEntry.builder()
                .id(UUID.randomUUID()).user(otherUser).content(movie).type(ContentType.MOVIE).position(1)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(watchlistEntryRepository.findById(otherEntry.getId())).thenReturn(Optional.of(otherEntry));

        assertThatThrownBy(() -> watchlistEntryService.removeEntry(
                userId, ContentType.MOVIE, otherEntry.getId()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Watchlist entry not found");
        verify(watchlistEntryRepository, never()).delete(any(WatchlistEntry.class));
    }

    @Test
    void shouldDoNothingWhenRemovingAbsentContent() {
        UUID contentId = UUID.randomUUID();
        when(watchlistEntryRepository.findByUserIdAndTypeAndContentId(
                userId, ContentType.MOVIE, contentId)).thenReturn(Optional.empty());

        watchlistEntryService.removeEntryIfPresent(userId, ContentType.MOVIE, contentId);

        verify(watchlistEntryRepository, never()).delete(any(WatchlistEntry.class));
        verify(watchlistEntryRepository, never()).flush();
    }

    @Test
    void shouldShiftGlobalPositionsWhenRemovingEntry() {
        WatchlistEntry toRemove = entry(series, ContentType.SERIES, 2);
        when(watchlistEntryRepository.findById(toRemove.getId())).thenReturn(Optional.of(toRemove));

        watchlistEntryService.removeEntry(userId, ContentType.SERIES, toRemove.getId());

        verify(watchlistEntryRepository).delete(toRemove);
        verify(watchlistEntryRepository).parkPositionsInRange(
                userId, 3, Integer.MAX_VALUE, WatchlistEntryServiceImpl.POSITION_PARK_OFFSET);
        verify(watchlistEntryRepository).settleParkedPositions(
                userId, WatchlistEntryServiceImpl.POSITION_PARK_OFFSET, -1);
    }

    @Test
    void shouldMoveEntryToEarlierGlobalPosition() {
        WatchlistEntry toMove = entry(series, ContentType.SERIES, 4);
        when(watchlistEntryRepository.findById(toMove.getId())).thenReturn(Optional.of(toMove));
        when(watchlistEntryRepository.countByUserId(userId)).thenReturn(4L);
        List<Integer> savedPositions = new ArrayList<>();
        when(watchlistEntryRepository.save(any(WatchlistEntry.class))).thenAnswer(invocation -> {
            savedPositions.add(((WatchlistEntry) invocation.getArgument(0)).getPosition());
            return invocation.getArgument(0);
        });
        when(watchlistEntryMapper.watchlistEntryToResponseDto(toMove)).thenReturn(response(toMove));

        watchlistEntryService.moveEntry(
                userId, ContentType.SERIES, toMove.getId(), new WatchlistEntryReorderDTO(2));

        assertThat(savedPositions).containsExactly(5, 2);
        verify(watchlistEntryRepository).parkPositionsInRange(
                userId, 2, 3, WatchlistEntryServiceImpl.POSITION_PARK_OFFSET);
        verify(watchlistEntryRepository).settleParkedPositions(
                userId, WatchlistEntryServiceImpl.POSITION_PARK_OFFSET, 1);
        verify(watchlistEntryRepository, times(2)).flush();
    }

    @Test
    void shouldMoveEntryToLaterGlobalPosition() {
        WatchlistEntry toMove = entry(movie, ContentType.MOVIE, 1);
        when(watchlistEntryRepository.findById(toMove.getId())).thenReturn(Optional.of(toMove));
        when(watchlistEntryRepository.countByUserId(userId)).thenReturn(4L);
        when(watchlistEntryRepository.save(any(WatchlistEntry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(watchlistEntryMapper.watchlistEntryToResponseDto(toMove)).thenReturn(response(toMove));

        watchlistEntryService.moveEntry(
                userId, ContentType.MOVIE, toMove.getId(), new WatchlistEntryReorderDTO(3));

        verify(watchlistEntryRepository).parkPositionsInRange(
                userId, 2, 3, WatchlistEntryServiceImpl.POSITION_PARK_OFFSET);
        verify(watchlistEntryRepository).settleParkedPositions(
                userId, WatchlistEntryServiceImpl.POSITION_PARK_OFFSET, -1);
    }

    @Test
    void shouldRejectMovePastGlobalEntryCount() {
        WatchlistEntry toMove = entry(movie, ContentType.MOVIE, 1);
        when(watchlistEntryRepository.findById(toMove.getId())).thenReturn(Optional.of(toMove));
        when(watchlistEntryRepository.countByUserId(userId)).thenReturn(1L);

        assertThatThrownBy(() -> watchlistEntryService.moveEntry(
                userId, ContentType.MOVIE, toMove.getId(), new WatchlistEntryReorderDTO(2)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("position cannot be greater than 1, the last position in the watchlist");
        verify(watchlistEntryRepository, never()).save(any(WatchlistEntry.class));
    }

    @Test
    void shouldMapConcurrentMoveFailureToConflict() {
        WatchlistEntry toMove = entry(movie, ContentType.MOVIE, 1);
        when(watchlistEntryRepository.findById(toMove.getId())).thenReturn(Optional.of(toMove));
        when(watchlistEntryRepository.countByUserId(userId)).thenReturn(2L);
        when(watchlistEntryRepository.save(any(WatchlistEntry.class)))
                .thenThrow(new DataIntegrityViolationException("concurrent modification"));

        assertThatThrownBy(() -> watchlistEntryService.moveEntry(
                userId, ContentType.MOVIE, toMove.getId(), new WatchlistEntryReorderDTO(2)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Watchlist entry could not be reordered due to a concurrent update");
    }

    @Test
    void shouldReturnWithoutSavingWhenMoveKeepsPosition() {
        WatchlistEntry toMove = entry(movie, ContentType.MOVIE, 2);
        when(watchlistEntryRepository.findById(toMove.getId())).thenReturn(Optional.of(toMove));
        when(watchlistEntryRepository.countByUserId(userId)).thenReturn(2L);
        WatchlistEntryResponseDTO expected = response(toMove);
        when(watchlistEntryMapper.watchlistEntryToResponseDto(toMove)).thenReturn(expected);

        assertThat(watchlistEntryService.moveEntry(
                userId, ContentType.MOVIE, toMove.getId(), new WatchlistEntryReorderDTO(2)))
                .isEqualTo(expected);
        verify(watchlistEntryRepository, never()).save(any(WatchlistEntry.class));
        verify(watchlistEntryRepository, never()).flush();
    }

    private void stubContentReference(Content content) {
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class))).thenReturn(
                new ContentRefDTO(content.getId(), content.getTmdbId(), content.getType(),
                        null, null, null, null, null, LocalDateTime.now(), LocalDateTime.now()));
    }

    private DataIntegrityViolationException dataIntegrityViolation(String constraintName) {
        return new DataIntegrityViolationException("db error", new ConstraintViolationException(
                "constraint violated", null, constraintName));
    }

    private void stubViewCandidates(List<WatchlistEntry> candidates) {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(watchlistEntryRepository.findByUserIdWithContentForView(userId)).thenReturn(candidates);
    }

    private void stubViewDates(List<WatchlistEntry> candidates, Map<UUID, LocalDate> dates) {
        when(releaseDateSnapshotService.resolve(user, candidates))
                .thenReturn(new ContentReleaseDateSnapshotService.WatchlistDateResolution(dates));
    }

    private void stubSeriesProgress(List<WatchlistEntry> candidates) {
        lenient().when(seriesProgressReadRepository.findProgressByUserIdAndSeriesTmdbIds(
                eq(userId), anyList())).thenReturn(List.of());
    }

    private void stubCards(List<WatchlistEntry> candidates) {
        when(contentCardAssembler.assemble(anyCollection(), any(ContentCardContext.class), anySet()))
                .thenAnswer(invocation -> {
                    Collection<ContentCardSpec> specs = invocation.getArgument(0);
                    Map<ContentCoordinate, ContentCardDTO> cards = new LinkedHashMap<>();
                    for (ContentCardSpec spec : specs) {
                        Content content = candidates.stream()
                                .map(WatchlistEntry::getContent)
                                .filter(candidate -> ContentCoordinate.from(candidate).equals(spec.coordinate()))
                                .findFirst()
                                .orElseThrow();
                        String tmdbId = content.getTmdbId();
                        double rating = switch (tmdbId) {
                            case "1" -> 8.0;
                            case "2" -> 9.0;
                            case "3" -> 7.0;
                            default -> 5.0;
                        };
                        cards.put(spec.coordinate(), card(content, tmdbId,
                                "Title " + tmdbId, ContentPreviewStatus.AVAILABLE, rating));
                    }
                    return cards;
                });
    }

    private List<String> viewIds(WatchlistSort sort, String direction) {
        return watchlistEntryService.getWatchlistView(
                        userId, userId, null, null, WatchlistStatus.ALL, sort, direction, 1, 10)
                .content().stream()
                .map(card -> card.card().tmdbId())
                .toList();
    }

    private ContentCardDTO card(
            Content content, String tmdbId, String title, ContentPreviewStatus previewStatus, Double rating) {
        return new ContentCardDTO(
                content.getId(), content.getType(), tmdbId, content.getSeriesTmdbId(),
                content.getSeasonNumber(), content.getEpisodeNumber(), title, "/poster.jpg", null,
                null, content.getReleaseYear(), content.getRuntimeMinutes(), content.getTotalRuntimeMinutes(),
                null, null, content.getGenres(),
                rating == null ? null : new ContentCardStatsDTO(rating, 0L, 0L, 0L), null, previewStatus);
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

    private Content content(String tmdbId, ContentType type, Integer runtimeMinutes, List<String> genres) {
        return Content.builder().id(UUID.randomUUID()).tmdbId(tmdbId).type(type)
                .runtimeMinutes(runtimeMinutes).genres(genres)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    private Content content(
            String tmdbId, ContentType type, Integer runtimeMinutes, List<String> genres, Integer totalRuntimeMinutes) {
        return Content.builder().id(UUID.randomUUID()).tmdbId(tmdbId).type(type)
                .runtimeMinutes(runtimeMinutes).totalRuntimeMinutes(totalRuntimeMinutes).genres(genres)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }
}
