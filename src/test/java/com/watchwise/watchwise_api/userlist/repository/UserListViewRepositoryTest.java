package com.watchwise.watchwise_api.userlist.repository;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.comment.entity.Comment;
import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.entity.Follower;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.like.entity.Like;
import com.watchwise.watchwise_api.like.repository.LikeRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userblock.entity.UserBlock;
import com.watchwise.watchwise_api.userlist.dto.*;
import com.watchwise.watchwise_api.userlist.entity.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.Assertions.tuple;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(UserListViewRepositoryImpl.class)
class UserListViewRepositoryTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired UserListViewRepository views;
    @Autowired UserRepository users;
    @Autowired UserListRepository lists;
    @Autowired UserListItemRepository items;
    @Autowired ContentRepository contents;
    @Autowired LikeRepository likes;
    @Autowired CommentRepository comments;
    @Autowired FollowerRepository followers;
    @Autowired EntityManager em;
    User viewer;
    User owner;

    @BeforeEach void setup() {
        viewer = users.save(user("view", true));
        owner = users.save(user("owner", false));
    }

    @Test
    @DisplayName("[findPage] Should Preserve Private Owned And Liked Lists Without Duplicating The Union - When Reading My Sources")
    void shouldPreserveMySourcesAndDefaultOrdersWhenPrivateLikedListsAreIncluded() {
        UserList mine = list(viewer, "mine", UserListVisibility.PRIVATE, 1, 2, null);
        UserList liked = list(owner, "liked", UserListVisibility.PRIVATE, 2, 1, null);
        UserList both = list(viewer, "both", UserListVisibility.PUBLIC, 3, 3, null);
        like(viewer, liked, 2);
        like(viewer, both, 1);
        em.flush(); em.clear();

        assertThat(ids(query(UserListViewQueryMode.MY, UserListViewSource.OWNED))).containsExactly(both.getId(), mine.getId());
        assertThat(ids(query(UserListViewQueryMode.MY, UserListViewSource.LIKED))).containsExactly(liked.getId(), both.getId());
        Page<UserList> all = views.findPage(query(UserListViewQueryMode.MY, UserListViewSource.ALL));
        assertThat(all.getTotalElements()).isEqualTo(3);
        assertThat(all.map(UserList::getId).getContent()).containsExactly(both.getId(), mine.getId(), liked.getId());
    }

    @Test
    @DisplayName("[findPage] Should Paginate The Owned And Liked Union Without Duplicates - When A List Is Both Owned And Liked")
    void shouldPaginateOwnedAndLikedUnionWithoutDuplicatesWhenSourceIsAll() {
        UserList oldest = list(owner, "liked", UserListVisibility.PRIVATE, 0, 1, null);
        UserList middle = list(viewer, "owned", UserListVisibility.PRIVATE, 0, 2, null);
        UserList newest = list(viewer, "owned-liked", UserListVisibility.PUBLIC, 0, 3, null);
        like(viewer, oldest, 1);
        like(viewer, newest, 2);
        em.flush(); em.clear();

        UserListViewQuery base = query(UserListViewQueryMode.MY, UserListViewSource.ALL);
        for (int page = 0; page < 3; page++) {
            UserListViewQuery paged = new UserListViewQuery(base.viewerId(), base.ownerId(), base.mode(),
                    base.source(), base.query(), base.itemType(), base.genre(), base.sortBy(),
                    base.direction(), PageRequest.of(page, 1));
            Page<UserList> result = views.findPage(paged);
            assertThat(result.getTotalElements()).isEqualTo(3);
            assertThat(result.getContent()).extracting(UserList::getId)
                    .containsExactly(List.of(newest, middle, oldest).get(page).getId());
        }
    }

    @Test
    @DisplayName("[findPage] Should Return An Empty Page With The Real Total - When Offset Exceeds The Count")
    void shouldReturnEmptyPageWithRealTotalWhenPageExceedsResults() {
        list(viewer, "only", UserListVisibility.PUBLIC, 0, 1, null);
        em.flush(); em.clear();
        UserListViewQuery base = query(UserListViewQueryMode.MY, UserListViewSource.OWNED);
        UserListViewQuery huge = new UserListViewQuery(base.viewerId(), null, base.mode(), base.source(),
                null, null, null, null, Sort.Direction.ASC, PageRequest.of(Integer.MAX_VALUE - 1, 10));

        Page<UserList> result = views.findPage(huge);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getNumber()).isEqualTo(Integer.MAX_VALUE - 1);
    }

    @Test
    @DisplayName("[findPage] Should Enforce The Private Profile Barrier And Accepted Follow - When Reading User Lists")
    void shouldHonorProfileVisibilityWhenQueryingUserMode() {
        UserList publicList = list(owner, "public", UserListVisibility.PUBLIC, 1, 1, null);
        UserList followersList = list(owner, "followers", UserListVisibility.FOLLOWERS, 1, 2, null);
        list(owner, "private", UserListVisibility.PRIVATE, 1, 3, null);
        em.flush(); em.clear();
        assertThat(ids(query(UserListViewQueryMode.USER, null))).isEmpty();
        followers.save(Follower.builder().follower(viewer).followed(owner).status(FollowStatus.ACCEPTED)
                .createdAt(LocalDateTime.now()).build());
        em.flush(); em.clear();
        assertThat(ids(query(UserListViewQueryMode.USER, null))).containsExactlyInAnyOrder(publicList.getId(), followersList.getId());
    }

    @Test
    @DisplayName("[findPage] Should Apply Public Visibility And Item Filters Before Pagination - When Discovering Lists")
    void shouldFilterPublicListsBeforePaginationWhenDiscovering() {
        UserList hidden = list(owner, "a hidden", UserListVisibility.PRIVATE, 100, 1, null);
        UserList first = list(viewer, "b public", UserListVisibility.PUBLIC, 5, 2, null);
        UserList second = list(owner, "c public", UserListVisibility.PUBLIC, 4, 3, null);
        Content movie = content(ContentType.MOVIE, "1", "Drama");
        item(first, movie, null, 1);
        item(first, content(ContentType.MOVIE, "4", "Drama"), null, 2);
        item(second, movie, null, 1); item(hidden, movie, null, 1);
        em.flush(); em.clear();
        UserListViewQuery q = new UserListViewQuery(viewer.getId(), null, UserListViewQueryMode.DISCOVER, null,
                null, UserListViewItemType.MOVIE, "drama", null, Sort.Direction.ASC, PageRequest.of(0, 1));
        Page<UserList> page = views.findPage(q);
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(UserList::getId).containsExactly(first.getId());
        assertThat(views.findPage(new UserListViewQuery(viewer.getId(), null, UserListViewQueryMode.DISCOVER, null,
                null, UserListViewItemType.MOVIE, "drama", null, Sort.Direction.ASC, PageRequest.of(1, 1)))
                .getContent()).extracting(UserList::getId).containsExactly(second.getId());
    }

    @Test
    void shouldExcludeBlockedOwnersBeforeVisualDiscoveryPaginationAndCount() {
        UserList blocked = list(owner, "A blocked owner list", UserListVisibility.PUBLIC, 100, 1, null);
        UserList visible = list(viewer, "B visible list", UserListVisibility.PUBLIC, 10, 2, null);
        em.persist(UserBlock.builder().blocker(viewer).blocked(owner).createdAt(LocalDateTime.now()).build());
        em.flush(); em.clear();
        UserListViewQuery query = new UserListViewQuery(viewer.getId(), null, UserListViewQueryMode.DISCOVER, null,
                null, null, null, null, Sort.Direction.DESC, PageRequest.of(0, 1));

        Page<UserList> result = views.findPage(query);

        assertThat(result.getContent()).extracting(UserList::getId).containsExactly(visible.getId());
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).doesNotContain(blocked);
    }

    @Test
    @DisplayName("[findPage] Should Search Own Public And Followed Lists By Relevance - When Search Mode Is Used")
    void shouldSearchVisibleListsByRelevanceWhenSearchModeIsUsed() {
        UserList exact = list(viewer, "night", UserListVisibility.PRIVATE, 1, 1, null);
        UserList prefix = list(owner, "night owls", UserListVisibility.FOLLOWERS, 1, 2, null);
        UserList contains = list(owner, "late night movies", UserListVisibility.PUBLIC, 10, 3, null);
        list(owner, "night secret", UserListVisibility.PRIVATE, 100, 4, null);
        followers.save(Follower.builder().follower(viewer).followed(owner).status(FollowStatus.ACCEPTED)
                .createdAt(LocalDateTime.now()).build());
        em.flush();
        em.clear();

        UserListViewQuery query = new UserListViewQuery(viewer.getId(), null, UserListViewQueryMode.SEARCH,
                null, "night", null, null, null, Sort.Direction.ASC, PageRequest.of(0, 10));

        Page<UserList> page = views.findPage(query);

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(UserList::getId)
                .containsExactly(exact.getId(), prefix.getId(), contains.getId());
    }

    @Test
    @DisplayName("[findPage] Should Rank A Literal Wildcard Name As Exact - When Search Query Contains A Wildcard")
    void shouldRankLiteralWildcardNameAsExactWhenSearchQueryContainsAWildcard() {
        UserList exact = list(viewer, "100%", UserListVisibility.PRIVATE, 1, 1, null);
        UserList prefix = list(viewer, "100% list extras", UserListVisibility.PRIVATE, 1, 2, null);
        em.flush();
        em.clear();

        UserListViewQuery query = new UserListViewQuery(viewer.getId(), null, UserListViewQueryMode.SEARCH,
                null, "100\\%", "100%", null, null, null, Sort.Direction.ASC, PageRequest.of(0, 10));

        assertThat(views.findPage(query).getContent()).extracting(UserList::getId)
                .containsExactly(exact.getId(), prefix.getId());
    }

    @Test
    @DisplayName("[countListsVisibleToViewerByOwnerIdIn] Should Exclude Private Lists From Other Owners - When Counting User Cards")
    void shouldExcludePrivateListsFromOtherOwnersWhenCountingUserCards() {
        list(viewer, "own-private", UserListVisibility.PRIVATE, 0, 1, null);
        list(owner, "public", UserListVisibility.PUBLIC, 0, 2, null);
        list(owner, "private", UserListVisibility.PRIVATE, 0, 3, null);
        followers.save(Follower.builder().follower(viewer).followed(owner).status(FollowStatus.ACCEPTED)
                .createdAt(LocalDateTime.now()).build());
        list(owner, "followers", UserListVisibility.FOLLOWERS, 0, 4, null);
        em.flush();
        em.clear();

        assertThat(lists.countListsVisibleToViewerByOwnerIdIn(viewer.getId(), List.of(viewer.getId(), owner.getId())))
                .extracting(UserListRepository.OwnerListCount::getUserId, UserListRepository.OwnerListCount::getCount)
                .containsExactlyInAnyOrder(
                        tuple(viewer.getId(), 1L), tuple(owner.getId(), 2L));
    }

    @Test
    @DisplayName("[findPage] Should Match Direct Genres And Keep Nested Lists Separate - When Filtering Content And List Items")
    void shouldKeepContentAndNestedListTypesSeparateWhenFiltering() {
        UserList parent = list(viewer, "parent", UserListVisibility.PUBLIC, 1, 1, null);
        UserList child = list(viewer, "child", UserListVisibility.PUBLIC, 1, 2, null);
        item(parent, content(ContentType.SEASON, "2", "Drama"), null, 1);
        item(parent, null, child, 2);
        em.flush(); em.clear();
        assertThat(ids(filter(UserListViewItemType.SEASON, null))).contains(parent.getId());
        assertThat(ids(filter(UserListViewItemType.LIST, null))).contains(parent.getId());
        assertThat(ids(filter(UserListViewItemType.MOVIE, null))).doesNotContain(parent.getId());
        assertThat(ids(filter(null, "Drama"))).doesNotContain(parent.getId(), child.getId());
        assertThat(ids(filter(UserListViewItemType.LIST, "Drama"))).doesNotContain(parent.getId());
    }

    @Test
    @DisplayName("[findPage] Should Resolve Season And Episode Genres Through Their Series - When Child Content Has No Genres")
    void shouldResolveSeasonAndEpisodeGenresThroughTheirSeriesWhenChildContentHasNoGenres() {
        UserList matching = list(viewer, "matching", UserListVisibility.PUBLIC, 0, 1, null);
        UserList differentSeries = list(viewer, "different-series", UserListVisibility.PUBLIC, 0, 2, null);
        UserList nestedOnly = list(viewer, "nested-only", UserListVisibility.PUBLIC, 0, 3, null);
        UserList child = list(viewer, "child", UserListVisibility.PUBLIC, 0, 4, null);

        contents.save(Content.builder().type(ContentType.SERIES).tmdbId("100")
                .genres(List.of("Drama")).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        contents.save(Content.builder().type(ContentType.SERIES).tmdbId("200")
                .genres(List.of("Comedy")).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        Content season = contentWithoutGenres(ContentType.SEASON, "100");
        Content episode = contentWithoutGenres(ContentType.EPISODE, "100");
        Content unrelatedEpisode = contentWithoutGenres(ContentType.EPISODE, "200");
        item(matching, season, null, 1);
        item(matching, episode, null, 2);
        item(differentSeries, unrelatedEpisode, null, 1);
        item(nestedOnly, null, child, 1);
        em.flush();
        em.clear();

        UserListViewQuery anyDrama = filter(null, "Drama");
        assertThat(views.findPage(anyDrama).getTotalElements()).isEqualTo(1);
        assertThat(ids(anyDrama)).containsExactly(matching.getId());
        assertThat(ids(filter(UserListViewItemType.SEASON, "Drama"))).containsExactly(matching.getId());
        assertThat(ids(filter(UserListViewItemType.EPISODE, "Drama"))).containsExactly(matching.getId());
        assertThat(ids(filter(UserListViewItemType.LIST, "Drama"))).isEmpty();
    }

    @Test
    @DisplayName("[findPage] Should Match Each Content Type Only To Its Own Items - When Filtering By Type")
    void shouldMatchOnlyOwnItemsWhenEachContentTypeIsFiltered() {
        for (ContentType type : ContentType.values()) {
            UserList list = list(viewer, type.name(), UserListVisibility.PUBLIC, 1, type.ordinal() + 1, null);
            item(list, content(type, String.valueOf(100 + type.ordinal()), "Drama"), null, 1);
        }
        em.flush(); em.clear();
        for (UserListViewItemType type : List.of(UserListViewItemType.MOVIE, UserListViewItemType.SERIES,
                UserListViewItemType.SEASON, UserListViewItemType.EPISODE)) {
            assertThat(views.findPage(filter(type, null)).getContent())
                    .extracting(UserList::getName).containsExactly(type.name());
        }
    }

    @Test
    @DisplayName("[findPage] Should Require Type And Genre On The Same Direct Item - When Both Filters Are Supplied")
    void shouldRequireTypeAndGenreOnTheSameItemWhenBothFiltersAreApplied() {
        UserList mixed = list(viewer, "mixed", UserListVisibility.PUBLIC, 1, 1, null);
        item(mixed, content(ContentType.MOVIE, "11", "Drama"), null, 1);
        item(mixed, content(ContentType.SERIES, "12", "Comedy"), null, 2);
        em.flush(); em.clear();
        UserListViewQuery query = new UserListViewQuery(viewer.getId(), null, UserListViewQueryMode.MY,
                UserListViewSource.OWNED, null, UserListViewItemType.SERIES, "Drama", null,
                Sort.Direction.ASC, PageRequest.of(0, 10));
        assertThat(ids(query)).doesNotContain(mixed.getId());
    }

    @Test
    @DisplayName("[findPage] Should Match Wildcard Characters Literally - When The Name Query Is Escaped")
    void shouldMatchWildcardCharactersLiterallyWhenSearchingNames() {
        UserList literal = list(viewer, "100%_\\", UserListVisibility.PUBLIC, 1, 1, null);
        list(viewer, "100ABC", UserListVisibility.PUBLIC, 2, 2, null);
        em.flush(); em.clear();
        UserListViewQuery query = new UserListViewQuery(viewer.getId(), null, UserListViewQueryMode.DISCOVER,
                null, "100\\%\\_\\\\", null, null, null, Sort.Direction.ASC, PageRequest.of(0, 10));
        assertThat(ids(query)).containsExactly(literal.getId());
    }

    @Test
    @DisplayName("[findNestedPreviewsByParentIdIn] Should Preserve Position And Child Owner Fields - When Projecting Nested Previews")
    void shouldPreservePositionAndOwnerFieldsWhenProjectingNestedPreviews() {
        UserList parent = list(viewer, "parent", UserListVisibility.PUBLIC, 0, 1, null);
        UserList second = list(owner, "second", UserListVisibility.PRIVATE, 0, 2, null);
        UserList first = list(owner, "first", UserListVisibility.PUBLIC, 0, 3, null);
        item(parent, null, second, 2);
        item(parent, null, first, 1);
        em.flush(); em.clear();
        var rows = items.findNestedPreviewsByParentIdIn(List.of(parent.getId()), viewer.getId());
        assertThat(rows).extracting(UserListItemRepository.NestedPreview::getChildId)
                .containsExactly(first.getId());
        assertThat(rows.get(0).getOwnerId()).isEqualTo(owner.getId());
        assertThat(rows.get(0).getOwnerName()).isEqualTo("owner");
    }

    @Test
    @DisplayName("[findNestedPreviewsByParentIdIn] Should Exclude Private Children Before The Preview Limit - When Later Public Children Exist")
    void shouldExcludePrivateChildrenBeforeLimitingVisiblePreviews() {
        UserList parent = list(viewer, "parent", UserListVisibility.PUBLIC, 0, 1, null);
        for (int position = 1; position <= 5; position++) {
            item(parent, null, list(owner, "hidden-" + position, UserListVisibility.PRIVATE,
                    0, position, null), position);
        }
        UserList visible = list(owner, "visible", UserListVisibility.PUBLIC, 0, 6, null);
        item(parent, null, visible, 6);
        em.flush(); em.clear();
        assertThat(items.findNestedPreviewsByParentIdIn(List.of(parent.getId()), viewer.getId()))
                .extracting(UserListItemRepository.NestedPreview::getChildId).containsExactly(visible.getId());
    }

    @Test
    @DisplayName("[findNestedPreviewsByParentIdIn] Should Return Public And Accepted Follower Children Only - When Viewer Is Not Owner")
    void shouldReturnPublicAndAcceptedFollowerChildrenOnlyWhenViewerIsNotOwner() {
        UserList parent = list(viewer, "parent", UserListVisibility.PUBLIC, 0, 1, null);
        UserList publicChild = list(owner, "public", UserListVisibility.PUBLIC, 0, 2, null);
        UserList acceptedFollowersChild = list(owner, "accepted-followers", UserListVisibility.FOLLOWERS, 0, 3, null);
        User otherOwner = users.save(user("other-owner", true));
        UserList unacceptedFollowersChild = list(
                otherOwner, "unaccepted-followers", UserListVisibility.FOLLOWERS, 0, 4, null);
        UserList privateChild = list(owner, "private", UserListVisibility.PRIVATE, 0, 5, null);
        followers.save(Follower.builder().follower(viewer).followed(owner).status(FollowStatus.ACCEPTED)
                .createdAt(LocalDateTime.now()).build());
        item(parent, null, publicChild, 1);
        item(parent, null, acceptedFollowersChild, 2);
        item(parent, null, unacceptedFollowersChild, 3);
        item(parent, null, privateChild, 4);
        em.flush();
        em.clear();

        assertThat(items.findNestedPreviewsByParentIdIn(List.of(parent.getId()), viewer.getId()))
                .extracting(UserListItemRepository.NestedPreview::getChildId)
                .containsExactly(publicChild.getId(), acceptedFollowersChild.getId());
    }

    @Test
    @DisplayName("[findPage] Should Sort Both Directions And Place Null Rank Last - When Sorting By Name Or Rank")
    void shouldSortBothDirectionsAndPlaceNullRanksLastWhenSortingByRank() {
        UserList a = list(viewer, "Alpha", UserListVisibility.PUBLIC, 1, 1, null);
        UserList b = list(viewer, "Beta", UserListVisibility.PUBLIC, 2, 2, 1);
        em.flush(); em.clear();
        assertThat(ids(sorted(UserListViewSort.NAME, Sort.Direction.ASC))).containsExactly(a.getId(), b.getId());
        assertThat(ids(sorted(UserListViewSort.NAME, Sort.Direction.DESC))).containsExactly(b.getId(), a.getId());
        assertThat(ids(sorted(UserListViewSort.RANK, Sort.Direction.ASC))).containsExactly(b.getId(), a.getId());
    }

    @Test
    @DisplayName("[findPage] Should Sort By Direct Item And Comment Counts - When Aggregate Sorts Are Requested")
    void shouldUseDirectCountsWhenSortingByItemsOrComments() {
        UserList low = list(viewer, "low", UserListVisibility.PUBLIC, 0, 1, null);
        UserList high = list(viewer, "high", UserListVisibility.PUBLIC, 0, 2, null);
        item(high, content(ContentType.MOVIE, "81", "Drama"), null, 1);
        item(high, content(ContentType.MOVIE, "82", "Drama"), null, 2);
        item(low, content(ContentType.MOVIE, "83", "Drama"), null, 1);
        comments.save(Comment.builder().user(viewer).list(high).text("comment")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        em.flush(); em.clear();
        assertThat(ids(sorted(UserListViewSort.ITEMS_COUNT, Sort.Direction.ASC)))
                .containsExactly(low.getId(), high.getId());
        assertThat(ids(sorted(UserListViewSort.ITEMS_COUNT, Sort.Direction.DESC)))
                .containsExactly(high.getId(), low.getId());
        assertThat(ids(sorted(UserListViewSort.COMMENTS_COUNT, Sort.Direction.DESC)))
                .containsExactly(high.getId(), low.getId());
    }

    @Test
    @DisplayName("[findPage] Should Use Like ID As The Stable Tie Breaker - When Like Timestamps Tie")
    void shouldUseLikeIdAsTieBreakerWhenLikeTimestampsTie() {
        UserList first = list(owner, "first", UserListVisibility.PUBLIC, 0, 1, null);
        UserList second = list(owner, "second", UserListVisibility.PUBLIC, 0, 2, null);
        Like firstLike = like(viewer, first, 1);
        Like secondLike = like(viewer, second, 1);
        em.flush(); em.clear();
        List<UUID> expected = firstLike.getId().toString().compareTo(secondLike.getId().toString()) > 0
                ? List.of(first.getId(), second.getId()) : List.of(second.getId(), first.getId());

        assertThat(ids(query(UserListViewQueryMode.MY, UserListViewSource.LIKED)))
                .containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("[findPage] Should Respect Both Sort Directions - When Sorting Owned Lists By Updated Time")
    void shouldRespectBothDirectionsWhenSortingOwnedListsByUpdatedAt() {
        UserList old = list(viewer, "old", UserListVisibility.PUBLIC, 0, 1, null);
        UserList recent = list(viewer, "recent", UserListVisibility.PUBLIC, 0, 2, null);
        em.flush(); em.clear();
        assertThat(ids(sorted(UserListViewSort.UPDATED_AT, Sort.Direction.ASC)))
                .containsExactly(old.getId(), recent.getId());
        assertThat(ids(sorted(UserListViewSort.UPDATED_AT, Sort.Direction.DESC)))
                .containsExactly(recent.getId(), old.getId());
    }

    @Test
    @DisplayName("[findPage] Should Use A Stable UUID Tie Breaker - When Sort Values And Discovery Defaults Tie")
    void shouldUseStableUuidTieBreakerWhenSortValuesTie() {
        UserList a = list(viewer, "same", UserListVisibility.PUBLIC, 2, 1, null);
        UserList b = list(viewer, "same", UserListVisibility.PUBLIC, 2, 1, null);
        em.flush(); em.clear();
        List<UUID> expected = List.of(a.getId(), b.getId()).stream()
                .sorted(Comparator.comparing(UUID::toString).reversed()).toList();
        assertThat(ids(sorted(UserListViewSort.NAME, Sort.Direction.ASC))).containsExactlyElementsOf(expected);
        assertThat(ids(sorted(UserListViewSort.NAME, Sort.Direction.DESC))).containsExactlyElementsOf(expected);
        assertThat(ids(query(UserListViewQueryMode.DISCOVER, null))).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("[findPage] Should Support Both Directions For Community Sorts - When Using User And Discovery Modes")
    void shouldSupportBothDirectionsForCommunitySortsWhenUsingUserAndDiscoveryModes() {
        owner.setIsProfilePublic(true);
        users.save(owner);
        UserList low = list(owner, "Alpha", UserListVisibility.PUBLIC, 1, 1, null);
        UserList high = list(owner, "Beta", UserListVisibility.PUBLIC, 2, 2, null);
        item(low, content(ContentType.MOVIE, "410", "Drama"), null, 1);
        item(high, content(ContentType.MOVIE, "411", "Drama"), null, 1);
        item(high, content(ContentType.MOVIE, "412", "Drama"), null, 2);
        comments.save(Comment.builder().user(viewer).list(low).text("one")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        comments.save(Comment.builder().user(viewer).list(high).text("two")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        comments.save(Comment.builder().user(viewer).list(high).text("three")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        em.flush(); em.clear();

        for (UserListViewQueryMode mode : List.of(UserListViewQueryMode.USER, UserListViewQueryMode.DISCOVER)) {
            for (UserListViewSort sort : List.of(UserListViewSort.NAME, UserListViewSort.LIKES_COUNT,
                    UserListViewSort.ITEMS_COUNT, UserListViewSort.COMMENTS_COUNT)) {
                UserListViewQuery base = query(mode, null);
                UserListViewQuery ascending = new UserListViewQuery(base.viewerId(), base.ownerId(), mode,
                        null, null, null, null, sort, Sort.Direction.ASC, PageRequest.of(0, 10));
                UserListViewQuery descending = new UserListViewQuery(base.viewerId(), base.ownerId(), mode,
                        null, null, null, null, sort, Sort.Direction.DESC, PageRequest.of(0, 10));
                assertThat(ids(ascending)).as("%s %s ascending", mode, sort)
                        .containsExactly(low.getId(), high.getId());
                assertThat(ids(descending)).as("%s %s descending", mode, sort)
                        .containsExactly(high.getId(), low.getId());
            }
        }
    }

    @Test
    @DisplayName("[findPage] Should Reject Invalid Mode And Source Combinations - When A Query Is Submitted")
    void shouldRejectInvalidModeSourceCombinationsWhenQuerying() {
        assertThatThrownBy(() -> views.findPage(query(UserListViewQueryMode.MY, null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> views.findPage(query(UserListViewQueryMode.USER, UserListViewSource.ALL))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> views.findPage(query(UserListViewQueryMode.DISCOVER, UserListViewSource.OWNED))).isInstanceOf(IllegalArgumentException.class);
    }

    private UserListViewQuery query(UserListViewQueryMode mode, UserListViewSource source) {
        return new UserListViewQuery(viewer.getId(), mode == UserListViewQueryMode.USER ? owner.getId() : null,
                mode, source, null, null, null, null, Sort.Direction.ASC, PageRequest.of(0, 10));
    }
    private UserListViewQuery filter(UserListViewItemType type, String genre) {
        return new UserListViewQuery(viewer.getId(), null, UserListViewQueryMode.MY, UserListViewSource.OWNED,
                null, type, genre, null, Sort.Direction.ASC, PageRequest.of(0, 10));
    }
    private UserListViewQuery sorted(UserListViewSort sort, Sort.Direction direction) {
        return new UserListViewQuery(viewer.getId(), null, UserListViewQueryMode.MY, UserListViewSource.OWNED,
                null, null, null, sort, direction, PageRequest.of(0, 10));
    }
    private List<UUID> ids(UserListViewQuery query) { return views.findPage(query).map(UserList::getId).getContent(); }
    private User user(String name, boolean publicProfile) {
        return User.builder().username(name + UUID.randomUUID().toString().substring(0, 6)).name(name)
                .email(UUID.randomUUID() + "@example.com").password("pass").isProfilePublic(publicProfile)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }
    private UserList list(User user, String name, UserListVisibility visibility, int likesCount, int minute, Integer rank) {
        return lists.save(UserList.builder().user(user).name(name).visibility(visibility).likesCount(likesCount)
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, minute)).updatedAt(LocalDateTime.of(2026, 1, 2, 0, minute))
                .rank(rank).build());
    }
    private Content content(ContentType type, String tmdb, String genre) {
        return contents.save(Content.builder().type(type).tmdbId(type == ContentType.MOVIE || type == ContentType.SERIES ? tmdb : null)
                .seriesTmdbId(type == ContentType.SEASON || type == ContentType.EPISODE ? tmdb : null)
                .seasonNumber(type == ContentType.SEASON || type == ContentType.EPISODE ? 1 : null)
                .episodeNumber(type == ContentType.EPISODE ? 1 : null)
                .genres(List.of(genre)).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
    }
    private Content contentWithoutGenres(ContentType type, String seriesTmdbId) {
        return contents.save(Content.builder().type(type).seriesTmdbId(seriesTmdbId).seasonNumber(1)
                .episodeNumber(type == ContentType.EPISODE ? 1 : null)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
    }
    private void item(UserList parent, Content content, UserList child, int position) {
        items.save(UserListItem.builder().userList(parent).content(content).childList(child).position(position)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
    }
    private Like like(User user, UserList list, int minute) {
        return likes.save(Like.builder().user(user).list(list)
                .createdAt(LocalDateTime.of(2026, 1, 3, 0, minute)).build());
    }
}
