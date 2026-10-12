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
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.like.service.LikeService;
import com.watchwise.watchwise_api.user.entity.User;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        lenient().when(commentRepository.countByListId(any())).thenReturn(4L);
        lenient().when(userListItemService.getItemScope(any())).thenReturn(UserListItemScope.MOVIE_OR_SERIES);
        lenient().when(diaryEntryRepository.findPublicEpisodeRatingAggregatesBySeriesTmdbIdIn(anyCollection()))
                .thenReturn(List.of());
        lenient().when(diaryEntryRepository.findContentStatsByContentIdIn(anyCollection())).thenReturn(List.of());
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
    @DisplayName("[read] Should Match Season And Episode Genres Through One Batched Series Lookup")
    void shouldMatchSeasonAndEpisodeGenresThroughOneBatchedSeriesLookup() {
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
    @DisplayName("[read] Should Filter Every Watch Status And Treat Missing State As Unknown")
    void shouldFilterEveryWatchStatusAndTreatMissingStateAsUnknown() {
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
    @DisplayName("[read] Should Reject Watch Status For List Scope")
    void shouldRejectWatchStatusForListScope() {
        stubListRead(List.of(nestedItem(1)), 0.0);
        when(userListItemService.getItemScope(listId)).thenReturn(UserListItemScope.LIST);

        assertThatThrownBy(() -> reader.read(request(null, null, null, null, WatchStatus.WATCHED, null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("watchStatus is only available for content lists");
    }

    @Test
    @DisplayName("[read] Should Omit Inaccessible Nested Lists From The Returned Page")
    void shouldOmitInaccessibleNestedListsFromTheReturnedPage() {
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
    @DisplayName("[read] Should Reject Private Owner Rating Sort Before Loading Items")
    void shouldRejectPrivateOwnerRatingSortBeforeLoadingItems() {
        owner.setIsProfilePublic(false);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(list));

        assertThatThrownBy(() -> reader.read(request(null, null, "episodeAvgRating", null, null, null, null)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("The list owner's episode ratings are private");
        verify(userListItemService, never()).getItemsWithState(any(), any());
    }

    private void stubListRead(List<UserListItemResponseDTO> items, double watchedPercentage) {
        when(userListRepository.findById(listId)).thenReturn(Optional.of(list));
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
