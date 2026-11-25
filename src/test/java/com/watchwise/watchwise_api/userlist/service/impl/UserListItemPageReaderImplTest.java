package com.watchwise.watchwise_api.userlist.service.impl;

import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.dto.ContentStateDTO;
import com.watchwise.watchwise_api.content.dto.WatchStatus;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentSchedule;
import com.watchwise.watchwise_api.content.service.ContentScheduleEpisode;
import com.watchwise.watchwise_api.content.service.ContentScheduleKey;
import com.watchwise.watchwise_api.content.service.ContentStateResolver;
import com.watchwise.watchwise_api.contentrating.repository.ContentRatingRepository;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.diaryentry.repository.WatchedEpisodeCoordinate;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.like.service.LikeService;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.userblock.service.UserBlockAccessService;
import com.watchwise.watchwise_api.userlist.dto.UserListItemPage;
import com.watchwise.watchwise_api.userlist.dto.UserListItemPageMode;
import com.watchwise.watchwise_api.userlist.dto.UserListItemPageRequest;
import com.watchwise.watchwise_api.userlist.dto.UserListItemResponseDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemScope;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import com.watchwise.watchwise_api.userlist.service.UserListItemService;
import com.watchwise.watchwise_api.userlist.service.UserListItemsWithState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserListItemPageReaderImplTest {

    @Mock
    private UserListRepository userListRepository;
    @Mock
    private FollowerRepository followerRepository;
    @Mock
    private UserListItemService userListItemService;
    @Mock
    private LikeService likeService;
    @Mock
    private CommentRepository commentRepository;
    @Mock
    private DiaryEntryRepository diaryEntryRepository;
    @Mock
    private ContentRepository contentRepository;
    @Mock
    private ContentRatingRepository contentRatingRepository;
    @Mock
    private UserBlockAccessService userBlockAccessService;
    @Spy
    private PageRequestFactory pageRequestFactory = new PageRequestFactory();
    @InjectMocks
    private UserListItemPageReaderImpl reader;

    private UUID ownerId;
    private UUID viewerId;
    private UUID listId;
    private User owner;
    private UserList list;

    @BeforeEach
    void setUp() {
        ownerId = UUID.randomUUID();
        viewerId = UUID.randomUUID();
        listId = UUID.randomUUID();
        owner = User.builder().id(ownerId).isProfilePublic(true).build();
        list = UserList.builder().id(listId).user(owner).name("Favorites")
                .visibility(UserListVisibility.PUBLIC).likesCount(7).rank(3).build();
        lenient().when(likeService.getLikedListIds(any(), anyCollection())).thenReturn(Set.of());
        lenient().when(userListItemService.getTotalRuntimeMinutes(any())).thenReturn(90L);
        lenient().when(commentRepository.countVisibleListComments(viewerId, List.of(listId)))
                .thenReturn(List.of(new CommentRepository.ListCommentCount() {
                    public UUID getListId() { return listId; }
                    public long getCount() { return 4L; }
                }));
        lenient().when(userListItemService.getItemScope(any())).thenReturn(UserListItemScope.MOVIE_OR_SERIES);
        lenient().when(diaryEntryRepository.findPublicEpisodeRatingAggregatesBySeriesTmdbIdIn(anyCollection()))
                .thenReturn(List.of());
        lenient().when(diaryEntryRepository.findContentStatsByContentIdIn(anyCollection())).thenReturn(List.of());
        lenient().when(contentRatingRepository.findPublicStatsByContentIdIn(anyCollection())).thenReturn(List.of());
    }

    @Test
    @DisplayName("[read] Should Return Filtered Page And Full List Metadata - When Reading A Visible List")
    void shouldReturnFilteredPageAndFullListMetadataWhenReadingAVisibleList() {
        List<UserListItemResponseDTO> items = IntStream.rangeClosed(1, 31)
                .mapToObj(position -> item(ContentType.MOVIE, position, List.of("Drama"), WatchStatus.UNWATCHED))
                .toList();
        stubListRead(items, 38.5);

        UserListItemPage result = reader.read(request(null, null, null, null, null, 2, 99));

        assertThat(result.items()).containsExactlyElementsOf(items.subList(30, 31));
        assertThat(result.watchedPercentage()).isEqualTo(38.5);
        assertThat(result.likesCount()).isEqualTo(7);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(30);
        assertThat(result.totalElements()).isEqualTo(31);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.hasNext()).isFalse();
        assertThat(result.itemsCount()).isEqualTo(31);
        assertThat(result.commentsCount()).isEqualTo(4);
        assertThat(result.totalRuntimeMinutes()).isEqualTo(90);
        assertThat(result.rank()).isEqualTo(3);
        verify(userListItemService).getItemsWithState(viewerId, listId);
    }

    @Test
    void shouldExcludeBlockedAuthorsFromItemPageCommentCount() {
        stubListRead(List.of(), 0.0);
        lenient().when(commentRepository.countVisibleListComments(viewerId, List.of(listId)))
                .thenReturn(List.of(new CommentRepository.ListCommentCount() {
                    public UUID getListId() { return listId; }
                    public long getCount() { return 1L; }
                }));

        UserListItemPage result = reader.read(request(null, null, null, null, null, null, null));

        assertThat(result.commentsCount()).isEqualTo(1L);
        verify(commentRepository, never()).countByListId(any());
    }

    @Test
    @DisplayName("[read] Should Match Season And Episode Genres Through One Batched Series Lookup - When Genre Matches A Persisted Series Parent")
    void shouldMatchSeasonAndEpisodeGenresThroughOneBatchedSeriesLookupWhenGenreMatchesAPersistedSeriesParent() {
        UserListItemResponseDTO season = item(ContentType.SEASON, 1, null, WatchStatus.UNKNOWN, "series-1", 1, null);
        UserListItemResponseDTO episode = item(ContentType.EPISODE, 2, null, WatchStatus.UNKNOWN, "series-2", 1, 1);
        UserListItemResponseDTO movie = item(ContentType.MOVIE, 3, List.of("Drama"), WatchStatus.UNKNOWN);
        stubListRead(List.of(season, episode, movie), 0.0);
        when(contentRepository.findByTypeAndTmdbIdIn(eq(ContentType.SERIES), anyCollection()))
                .thenReturn(List.of(series("series-1", List.of("Drama")), series("series-2", List.of("Comedy"))));

        UserListItemPage result = reader.read(request(null, "Drama", null, null, null, null, null));

        assertThat(result.items()).containsExactly(season, movie);
        verify(contentRepository).findByTypeAndTmdbIdIn(eq(ContentType.SERIES), eq(Set.of("series-1", "series-2")));
    }

    @Test
    @DisplayName("[read] Should Filter Every Watch Status And Treat Missing State As Unknown - When Each Status Is Requested")
    void shouldFilterEveryWatchStatusAndTreatMissingStateAsUnknownWhenEachStatusIsRequested() {
        List<UserListItemResponseDTO> items = List.of(
                item(ContentType.MOVIE, 1, null, WatchStatus.WATCHED),
                item(ContentType.MOVIE, 2, null, WatchStatus.PARTIALLY_WATCHED),
                item(ContentType.MOVIE, 3, null, WatchStatus.UNWATCHED),
                item(ContentType.MOVIE, 4, null, WatchStatus.UNKNOWN),
                itemWithoutState(ContentType.MOVIE, 5));
        stubListRead(items, 20.0);

        for (WatchStatus status : WatchStatus.values()) {
            UserListItemPage result = reader.read(request(null, null, null, null, status, null, null));
            List<Integer> expectedPositions = switch (status) {
                case WATCHED -> List.of(1);
                case PARTIALLY_WATCHED -> List.of(2);
                case UNWATCHED -> List.of(3);
                case UNKNOWN -> List.of(4, 5);
            };
            assertThat(result.items()).extracting(item -> item.position()).containsExactlyElementsOf(expectedPositions);
        }
    }

    @Test
    @DisplayName("[read] Should Reject Watch Status For List Scope - When The List Contains Nested Items")
    void shouldRejectWatchStatusForListScopeWhenTheListContainsNestedItems() {
        stubListRead(List.of(nestedItem(1)), 0.0);
        when(userListItemService.getItemScope(listId)).thenReturn(UserListItemScope.LIST);

        assertThatThrownBy(() -> reader.read(request(null, null, null, null, WatchStatus.WATCHED, null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("watchStatus is only available for content lists");
    }

    @Test
    @DisplayName("[read] Should Omit Inaccessible Nested Lists From The Returned Page - When Reading Visual Mode")
    void shouldOmitInaccessibleNestedListsFromTheReturnedPageWhenReadingVisualMode() {
        UserListItemResponseDTO hiddenNestedList = new UserListItemResponseDTO(
                UUID.randomUUID(), null, null, 1, null, LocalDateTime.now(), LocalDateTime.now(), null, null);
        UserListItemResponseDTO movie = item(ContentType.MOVIE, 2, null, WatchStatus.UNWATCHED);
        stubListRead(List.of(hiddenNestedList, movie), 0.0);

        UserListItemPage result = reader.read(request(UserListItemPageMode.VISUAL, null, null, null, null, null, null, null));

        assertThat(result.items()).containsExactly(movie);
        assertThat(result.itemsCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("[read] Should Preserve Inaccessible Nested List Placeholder Rows - When Reading Raw Mode")
    void shouldPreserveInaccessibleNestedListPlaceholderRowsWhenReadingRawMode() {
        UserListItemResponseDTO inaccessiblePlaceholder = new UserListItemResponseDTO(
                UUID.randomUUID(), null, null, 1, null, LocalDateTime.now(), LocalDateTime.now(), null, null);
        UserListItemResponseDTO movie = item(ContentType.MOVIE, 2, null, WatchStatus.UNWATCHED);
        stubListRead(List.of(inaccessiblePlaceholder, movie), 0.0);
        when(userListItemService.getItemScope(listId)).thenReturn(UserListItemScope.LIST);

        UserListItemPage result = reader.read(request(UserListItemPageMode.RAW, null, null, null, null, null, null, null));

        assertThat(result.items()).containsExactly(inaccessiblePlaceholder, movie);
        assertThat(result.itemsCount()).isEqualTo(2);
        assertThat(result.totalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("[read] Should Exclude Status Filtered Items From Rating Queries - When Filtering By Watch Status")
    void shouldExcludeStatusFilteredItemsFromRatingQueriesWhenFilteringByWatchStatus() {
        UserListItemResponseDTO included = item(
                ContentType.EPISODE, 1, null, WatchStatus.WATCHED, "series-included", 1, 1);
        UserListItemResponseDTO excluded = item(
                ContentType.EPISODE, 2, null, WatchStatus.UNWATCHED, "series-excluded", 1, 2);
        stubListRead(List.of(included, excluded), 50.0);
        when(userListItemService.getItemScope(listId)).thenReturn(UserListItemScope.EPISODE);

        reader.read(request(UserListItemPageMode.VISUAL, null, null, null, null, WatchStatus.WATCHED, null, null));

        verify(diaryEntryRepository).findScoredEpisodeEntriesByUserIdAndSeriesTmdbIdIn(
                ownerId, Set.of("series-included"));
        verify(diaryEntryRepository).findPublicEpisodeRatingAggregatesBySeriesTmdbIdIn(
                Set.of("series-included"));
        verify(diaryEntryRepository).findContentStatsByContentIdIn(Set.of(included.content().id()));
    }

    @Test
    @DisplayName("[read] Should Reject Private Owner Rating Sort Before Loading Items - When The Owner Profile Is Private")
    void shouldRejectPrivateOwnerRatingSortBeforeLoadingItemsWhenTheOwnerProfileIsPrivate() {
        owner.setIsProfilePublic(false);
        when(userListRepository.findByIdWithUser(listId)).thenReturn(Optional.of(list));

        assertThatThrownBy(() -> reader.read(request(null, null, "episodeAvgRating", null, null, null, null)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("The list owner's episode ratings are private");
        verify(userListItemService, never()).getItemsWithState(any(), any());
    }

    @ParameterizedTest
    @CsvSource({
            "position,asc,1,1,2", "position,asc,2,3,4", "position,asc,3,5,0",
            "position,desc,1,5,4", "dateAdded,asc,1,2,4", "dateAdded,asc,2,1,3",
            "dateAdded,desc,1,5,1", "duration,asc,1,3,1", "duration,asc,2,4,2",
            "duration,desc,1,2,5", "NULL,desc,1,1,2"
    })
    void shouldEnrichOnlyTheSelectedVisualPageForIndependentSorts(
            String sort, String direction, int page, int first, int second) {
        List<UserListItemResponseDTO> items = List.of(
                sortableItem(ContentType.MOVIE, 1, 2, 70),
                sortableItem(ContentType.SERIES, 2, 1, 90),
                sortableItem(ContentType.MOVIE, 3, 2, null),
                sortableItem(ContentType.SERIES, 4, 1, 70),
                sortableItem(ContentType.SERIES, 5, 3, 90));
        stubListRead(items, 40.0);
        List<UserListItemResponseDTO> expected = second == 0
                ? List.of(items.get(first - 1)) : List.of(items.get(first - 1), items.get(second - 1));

        Double score = 25.0 / 3;
        DiaryEntryRepository.ContentStats movieStats = mock(DiaryEntryRepository.ContentStats.class);
        lenient().when(movieStats.getContentId()).thenReturn(items.getFirst().content().id());
        lenient().when(movieStats.getAverageScore()).thenReturn(score);
        lenient().when(diaryEntryRepository.findContentStatsByContentIdIn(anyCollection()))
                .thenAnswer(call -> call.<Set<UUID>>getArgument(0).contains(items.getFirst().content().id())
                        ? List.of(movieStats) : List.of());

        UserListItemPage result = reader.read(request(
                null, null, "NULL".equals(sort) ? null : sort, direction, null, page, 2));

        Set<UUID> movies = expected.stream()
                .filter(row -> row.content().type() == ContentType.MOVIE)
                .map(row -> row.content().id()).collect(Collectors.toSet());
        List<ContentRefDTO> series = expected.stream().map(UserListItemResponseDTO::content)
                .filter(content -> content.type() == ContentType.SERIES).toList();
        verify(diaryEntryRepository, times(movies.isEmpty() ? 0 : 1)).findContentStatsByContentIdIn(eq(movies));
        verify(diaryEntryRepository, times(series.isEmpty() ? 0 : 1))
                .findPublicEpisodeRatingAggregatesBySeriesTmdbIdIn(
                        eq(series.stream().map(ContentRefDTO::tmdbId).collect(Collectors.toSet())));
        verify(diaryEntryRepository, times(series.isEmpty() ? 0 : 1))
                .findScoredEpisodeEntriesByUserIdAndSeriesTmdbIdIn(
                        eq(ownerId), eq(series.stream().map(ContentRefDTO::tmdbId).collect(Collectors.toSet())));
        verify(contentRatingRepository, times(series.isEmpty() ? 0 : 1))
                .findPublicStatsByContentIdIn(eq(series.stream().map(ContentRefDTO::id).collect(Collectors.toSet())));
        verifyNoMoreInteractions(diaryEntryRepository, contentRatingRepository);
        assertThat(result.items()).containsExactlyElementsOf(expected.stream().map(row ->
                row.position() == 1 ? row.withRatingAverages(null, null, score) : row).toList());
        assertThat(result.totalElements()).isEqualTo(5);
        assertThat(result.itemsCount()).isEqualTo(5);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.hasNext()).isEqualTo(page < 3);
        assertThat(result.watchedPercentage()).isEqualTo(40.0);
    }

    @ParameterizedTest
    @CsvSource({
            "episodeAvgRating,asc,1,1,2", "episodeAvgRating,desc,1,2,3",
            "globalEpisodeAvgRating,asc,1,1,2", "globalEpisodeAvgRating,desc,1,3,1",
            "contentAvgRating,asc,1,3,1", "contentAvgRating,desc,1,1,3",
            "contentAvgRating,asc,2,2,4", "contentAvgRating,desc,2,2,4",
            "episodeAvgRating,desc,2,1,4", "globalEpisodeAvgRating,desc,2,2,4"
    })
    void shouldSortUsingRatingsFromAllCandidatesWithStableTiesAndNullsLast(
            String sort, String direction, int page, int first, int second) {
        List<UserListItemResponseDTO> items = IntStream.rangeClosed(1, 4)
                .mapToObj(position -> sortableItem(ContentType.SERIES, position, 1, 90)).toList();
        stubListRead(items, 0);
        Set<String> tmdbIds = items.stream().map(row -> row.content().tmdbId()).collect(Collectors.toSet());
        Set<UUID> contentIds = items.stream().map(row -> row.content().id()).collect(Collectors.toSet());
        when(diaryEntryRepository.findScoredEpisodeEntriesByUserIdAndSeriesTmdbIdIn(ownerId, tmdbIds))
                .thenReturn(List.of(ownerScore("tmdb-1", 2), ownerScore("tmdb-2", 8), ownerScore("tmdb-3", 8)));
        List<DiaryEntryRepository.PublicEpisodeRatingAggregate> episodeStats = List.of(
                publicEpisodeScore("tmdb-1", 1, 7L, 1L), publicEpisodeScore("tmdb-1", 2, 14L, 3L),
                publicEpisodeScore("tmdb-2", 1, 21L, 4L), publicEpisodeScore("tmdb-3", 1, 9L, 1L));
        List<ContentRatingRepository.PublicRatingStats> contentStats = List.of(
                publicRating(items.getFirst(), 7.25), publicRating(items.get(2), 2.5));
        when(diaryEntryRepository.findPublicEpisodeRatingAggregatesBySeriesTmdbIdIn(tmdbIds)).thenReturn(episodeStats);
        when(contentRatingRepository.findPublicStatsByContentIdIn(contentIds)).thenReturn(contentStats);

        UserListItemPage result = reader.read(request(null, null, sort, direction, null, page, 2));

        List<UserListItemResponseDTO> baseline = List.of(items.get(0).withRatingAverages(2.0, 5.25, 7.25),
                items.get(1).withRatingAverages(8.0, 5.25, null),
                items.get(2).withRatingAverages(8.0, 9.0, 2.5), items.get(3));
        assertThat(result.items()).containsExactly(baseline.get(first - 1), baseline.get(second - 1));
        assertThat(result.totalElements()).isEqualTo(4);
        assertThat(result.totalPages()).isEqualTo(2);
        verify(diaryEntryRepository).findScoredEpisodeEntriesByUserIdAndSeriesTmdbIdIn(ownerId, tmdbIds);
        verify(diaryEntryRepository).findPublicEpisodeRatingAggregatesBySeriesTmdbIdIn(tmdbIds);
        verify(contentRatingRepository).findPublicStatsByContentIdIn(contentIds);
        verifyNoMoreInteractions(diaryEntryRepository, contentRatingRepository);
    }

    @Test
    void shouldNotQueryRatingsForAnEmptyOrOutOfRangeVisualPage() {
        stubListRead(List.of(sortableItem(ContentType.MOVIE, 1, 1, 50)), 0);

        UserListItemPage outside = reader.read(request(null, null, "position", "asc", null, 2, 1));
        UserListItemPage empty = reader.read(request(ContentType.SERIES, "Drama", "position", "asc", null, 1, 1));

        assertThat(outside.items()).isEmpty();
        assertThat(outside.totalElements()).isEqualTo(1);
        assertThat(empty.items()).isEmpty();
        assertThat(empty.totalElements()).isZero();
        assertThat(empty.totalPages()).isZero();
        assertThat(empty.itemsCount()).isEqualTo(1);
        verifyNoInteractions(diaryEntryRepository, contentRatingRepository);
    }

    @Test
    void shouldPreserveGlobalEnrichmentForTheRawContract() {
        UserListItemResponseDTO first = sortableItem(ContentType.MOVIE, 1, 1, 50);
        UserListItemResponseDTO second = sortableItem(ContentType.MOVIE, 2, 2, 60);
        stubListRead(List.of(first, second), 0);

        UserListItemPage result = reader.read(
                request(UserListItemPageMode.RAW, null, null, "position", "asc", null, 2, 1));

        assertThat(result.items()).containsExactly(second);
        verify(diaryEntryRepository).findContentStatsByContentIdIn(Set.of(first.content().id(), second.content().id()));
    }

    @Test
    void shouldKeepTheVisualPageIdenticalToTheGlobalEnrichmentReference() {
        UserListItemResponseDTO first = sortableItem(ContentType.SERIES, 1, 1, 75);
        UserListItemResponseDTO second = seriesWithResolvedState(2, WatchStatus.PARTIALLY_WATCHED);
        stubListRead(List.of(first, second), 30.0);
        ContentRatingRepository.PublicRatingStats rating = publicRating(second, 19.0 / 3);
        when(contentRatingRepository.findPublicStatsByContentIdIn(Set.of(first.content().id(), second.content().id())))
                .thenReturn(List.of(rating));
        when(contentRatingRepository.findPublicStatsByContentIdIn(Set.of(second.content().id())))
                .thenReturn(List.of(rating));

        UserListItemPage global = reader.read(
                request(UserListItemPageMode.RAW, null, null, "position", "asc", null, 2, 1));
        UserListItemPage paged = reader.read(
                request(UserListItemPageMode.VISUAL, null, null, "position", "asc", null, 2, 1));

        assertThat(paged).isEqualTo(global);
        assertThat(paged.items()).containsExactly(second.withRatingAverages(null, null, 19.0 / 3));
    }

    @Test
    void shouldRejectPrivateListForThirdPartyAndAllowItsOwner() {
        list.setVisibility(UserListVisibility.PRIVATE);
        owner.setIsProfilePublic(false);
        when(userListRepository.findByIdWithUser(listId)).thenReturn(Optional.of(list));
        assertThatThrownBy(() -> reader.read(request(null, null, "position", "asc", null, 1, 1)))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(userListItemService, diaryEntryRepository, contentRatingRepository);

        UserListItemResponseDTO entry = sortableItem(ContentType.SERIES, 1, 1, 50);
        when(userListItemService.getItemsWithState(ownerId, listId))
                .thenReturn(new UserListItemsWithState(List.of(entry), 0));
        UserListItemPage ownerPage = reader.read(new UserListItemPageRequest(
                ownerId, listId, null, null, "position", "asc", null, 1, 1, UserListItemPageMode.VISUAL));
        assertThat(ownerPage.items()).containsExactly(entry);
        verify(diaryEntryRepository)
                .findScoredEpisodeEntriesByUserIdAndSeriesTmdbIdIn(ownerId, Set.of(entry.content().tmdbId()));
    }

    @ParameterizedTest
    @EnumSource(WatchStatus.class)
    void shouldPreserveResolvedStatesAndFilterThemBeforeSelectingTheNotesPage(WatchStatus requested) {
        List<UserListItemResponseDTO> items = List.of(
                seriesWithResolvedState(1, WatchStatus.WATCHED), seriesWithResolvedState(2, WatchStatus.UNWATCHED),
                seriesWithResolvedState(3, WatchStatus.PARTIALLY_WATCHED), seriesWithResolvedState(4, WatchStatus.UNKNOWN),
                seriesWithResolvedState(5, WatchStatus.WATCHED), seriesWithResolvedState(6, WatchStatus.UNWATCHED),
                seriesWithResolvedState(7, WatchStatus.PARTIALLY_WATCHED), seriesWithResolvedState(8, WatchStatus.UNKNOWN),
                sortableItem(ContentType.MOVIE, 9, 1, 80));
        stubListRead(items, 100.0 * 2 / 9);
        List<UserListItemResponseDTO> matching = items.subList(0, 8).stream()
                .filter(entry -> entry.contentState().watchStatus() == requested).toList();
        assertThat(matching).hasSize(2);
        UserListItemResponseDTO expected = matching.get(1);
        ContentRatingRepository.PublicRatingStats rating = publicRating(expected, 6.125);
        when(contentRatingRepository.findPublicStatsByContentIdIn(Set.of(expected.content().id())))
                .thenReturn(List.of(rating));

        UserListItemPage result = reader.read(request(
                ContentType.SERIES, "Drama", "position", "asc", requested, 2, 1));

        assertThat(result.items()).containsExactly(expected.withRatingAverages(null, null, 6.125));
        ContentStateDTO state = result.items().getFirst().contentState();
        assertThat(state.watchStatus()).isEqualTo(requested);
        assertThat(state.releasedEpisodeCount()).isEqualTo(requested == WatchStatus.UNKNOWN ? null : 2);
        assertThat(state.watchedEpisodeCount()).isEqualTo(switch (requested) {
            case WATCHED -> 2;
            case PARTIALLY_WATCHED -> 1;
            case UNWATCHED -> 0;
            case UNKNOWN -> null;
        });
        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.hasNext()).isFalse();
        assertThat(result.itemsCount()).isEqualTo(9);
        assertThat(result.watchedPercentage()).isEqualTo(100.0 * 2 / 9);
        verify(diaryEntryRepository)
                .findPublicEpisodeRatingAggregatesBySeriesTmdbIdIn(Set.of(expected.content().tmdbId()));
        verify(contentRatingRepository).findPublicStatsByContentIdIn(Set.of(expected.content().id()));
        verifyNoMoreInteractions(contentRatingRepository);
    }

    private UserListItemResponseDTO sortableItem(ContentType type, int position, int day, Integer runtime) {
        LocalDateTime date = LocalDateTime.of(2026, 1, day, 12, 0);
        ContentRefDTO content = new ContentRefDTO(UUID.randomUUID(), "tmdb-" + position, type,
                null, null, null, null, null, date, date, runtime, List.of("Drama"));
        return new UserListItemResponseDTO(UUID.randomUUID(), content, null, position, "description",
                date, date, "/poster.jpg", new ContentStateDTO(WatchStatus.UNWATCHED, null, null, null, null));
    }

    private UserListItemResponseDTO seriesWithResolvedState(int position, WatchStatus requested) {
        UserListItemResponseDTO row = sortableItem(ContentType.SERIES, position, 1, 90);
        String tmdbId = row.content().tmdbId();
        LocalDate cutoff = LocalDate.of(2026, 1, 10);
        Clock clock = Clock.fixed(cutoff.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        Content series = Content.builder().id(row.content().id()).type(ContentType.SERIES).tmdbId(tmdbId).build();
        ContentSchedule schedule = new ContentSchedule(ContentScheduleKey.series(tmdbId), cutoff.minusDays(4),
                "Returning Series", List.of(new ContentScheduleEpisode(tmdbId, 1, 1, cutoff.minusDays(1)),
                        new ContentScheduleEpisode(tmdbId, 1, 2, cutoff),
                        new ContentScheduleEpisode(tmdbId, 1, 3, cutoff.plusDays(1))),
                requested != WatchStatus.UNKNOWN, false);
        Set<WatchedEpisodeCoordinate> watched = switch (requested) {
            case WATCHED -> Set.of(new WatchedEpisodeCoordinate(tmdbId, 1, 1),
                    new WatchedEpisodeCoordinate(tmdbId, 1, 2));
            case PARTIALLY_WATCHED, UNKNOWN -> Set.of(new WatchedEpisodeCoordinate(tmdbId, 1, 1));
            case UNWATCHED -> Set.of();
        };
        ContentStateDTO state = new ContentStateResolver().resolve(series, schedule, Set.of(), watched, clock);
        return new UserListItemResponseDTO(row.id(), row.content(), null, position, row.description(),
                row.createdAt(), row.updatedAt(), row.customPosterUrl(), state);
    }

    private DiaryEntry ownerScore(String series, int score) {
        return DiaryEntry.builder().content(Content.builder().type(ContentType.EPISODE)
                .seriesTmdbId(series).seasonNumber(1).episodeNumber(1).build()).score(score).build();
    }

    private DiaryEntryRepository.PublicEpisodeRatingAggregate publicEpisodeScore(
            String series, int episode, long sum, long count) {
        DiaryEntryRepository.PublicEpisodeRatingAggregate result =
                mock(DiaryEntryRepository.PublicEpisodeRatingAggregate.class);
        when(result.getSeriesTmdbId()).thenReturn(series);
        when(result.getSeasonNumber()).thenReturn(1);
        when(result.getEpisodeNumber()).thenReturn(episode);
        when(result.getScoreSum()).thenReturn(sum);
        when(result.getScoreCount()).thenReturn(count);
        return result;
    }

    private ContentRatingRepository.PublicRatingStats publicRating(UserListItemResponseDTO row, double score) {
        ContentRatingRepository.PublicRatingStats result = mock(ContentRatingRepository.PublicRatingStats.class);
        when(result.getContentId()).thenReturn(row.content().id());
        when(result.getAverageScore()).thenReturn(score);
        return result;
    }

    private void stubListRead(List<UserListItemResponseDTO> items, double watchedPercentage) {
        when(userListRepository.findByIdWithUser(listId)).thenReturn(Optional.of(list));
        when(userListItemService.getItemsWithState(viewerId, listId))
                .thenReturn(new UserListItemsWithState(items, watchedPercentage));
    }

    private UserListItemPageRequest request(ContentType type, String genre, String sortBy, String sortDirection,
            WatchStatus watchStatus, Integer page, Integer size) {
        return request(UserListItemPageMode.VISUAL, type, genre, sortBy, sortDirection, watchStatus, page, size);
    }

    private UserListItemPageRequest request(UserListItemPageMode mode, ContentType type, String genre, String sortBy,
            String sortDirection, WatchStatus watchStatus, Integer page, Integer size) {
        return new UserListItemPageRequest(
                viewerId, listId, type, genre, sortBy, sortDirection, watchStatus, page, size, mode);
    }

    private UserListItemResponseDTO item(ContentType type, int position, List<String> genres, WatchStatus status) {
        return item(type, position, genres, status, null, null, null);
    }

    private UserListItemResponseDTO item(ContentType type, int position, List<String> genres, WatchStatus status,
            String seriesId, Integer seasonNumber, Integer episodeNumber) {
        ContentRefDTO content = new ContentRefDTO(UUID.randomUUID(),
                type == ContentType.MOVIE || type == ContentType.SERIES ? "tmdb-" + position : null,
                type, seriesId, seasonNumber, episodeNumber, null, null, LocalDateTime.now(), LocalDateTime.now(),
                90, genres, null, null);
        return new UserListItemResponseDTO(UUID.randomUUID(), content, null, position, null,
                LocalDateTime.now(), LocalDateTime.now(), null,
                new ContentStateDTO(status, null, null, null, null));
    }

    private UserListItemResponseDTO itemWithoutState(ContentType type, int position) {
        UserListItemResponseDTO item = item(type, position, null, WatchStatus.UNKNOWN);
        return new UserListItemResponseDTO(item.id(), item.content(), null, item.position(), item.description(),
                item.createdAt(), item.updatedAt(), item.customPosterUrl(), null);
    }

    private UserListItemResponseDTO nestedItem(int position) {
        return new UserListItemResponseDTO(UUID.randomUUID(), null,
                new com.watchwise.watchwise_api.userlist.dto.UserListPreviewDTO(
                        UUID.randomUUID(), null, "Nested", UserListVisibility.PUBLIC),
                position, null, LocalDateTime.now(), LocalDateTime.now(), null, null);
    }

    private Content series(String tmdbId, List<String> genres) {
        return Content.builder().id(UUID.randomUUID()).type(ContentType.SERIES).tmdbId(tmdbId).genres(genres).build();
    }
}
