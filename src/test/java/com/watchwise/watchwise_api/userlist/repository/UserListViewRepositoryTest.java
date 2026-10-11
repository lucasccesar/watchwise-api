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
import com.watchwise.watchwise_api.userlist.dto.*;
import com.watchwise.watchwise_api.userlist.entity.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

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

    @Test void mySourcesAndDefaultOrdersPreservePrivateLikedListsAndDistinctUnion() {
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

    @Test void userModeHonorsPrivateProfileBarrierAndAcceptedFollow() {
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

    @Test void discoveryAppliesPublicAndItemFiltersBeforePagination() {
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

    @Test void directContentTypesAndNestedListTypeDoNotCrossMatch() {
        UserList parent = list(viewer, "parent", UserListVisibility.PUBLIC, 1, 1, null);
        UserList child = list(viewer, "child", UserListVisibility.PUBLIC, 1, 2, null);
        item(parent, content(ContentType.SEASON, "2", "Drama"), null, 1);
        item(parent, null, child, 2);
        em.flush(); em.clear();
        assertThat(ids(filter(UserListViewItemType.SEASON, null))).contains(parent.getId());
        assertThat(ids(filter(UserListViewItemType.LIST, null))).contains(parent.getId());
        assertThat(ids(filter(UserListViewItemType.MOVIE, null))).doesNotContain(parent.getId());
        assertThat(ids(filter(null, "Drama"))).contains(parent.getId()).doesNotContain(child.getId());
    }

    @Test void allFourContentTypesMatchOnlyTheirOwnDirectItems() {
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

    @Test void typeAndGenreMustMatchTheSameDirectItem() {
        UserList mixed = list(viewer, "mixed", UserListVisibility.PUBLIC, 1, 1, null);
        item(mixed, content(ContentType.MOVIE, "11", "Drama"), null, 1);
        item(mixed, content(ContentType.SERIES, "12", "Comedy"), null, 2);
        em.flush(); em.clear();
        UserListViewQuery query = new UserListViewQuery(viewer.getId(), null, UserListViewQueryMode.MY,
                UserListViewSource.OWNED, null, UserListViewItemType.SERIES, "Drama", null,
                Sort.Direction.ASC, PageRequest.of(0, 10));
        assertThat(ids(query)).doesNotContain(mixed.getId());
    }

    @Test void escapedNameQueryMatchesLiteralWildcardCharacters() {
        UserList literal = list(viewer, "100%_\\", UserListVisibility.PUBLIC, 1, 1, null);
        list(viewer, "100ABC", UserListVisibility.PUBLIC, 2, 2, null);
        em.flush(); em.clear();
        UserListViewQuery query = new UserListViewQuery(viewer.getId(), null, UserListViewQueryMode.DISCOVER,
                null, "100\\%\\_\\\\", null, null, null, Sort.Direction.ASC, PageRequest.of(0, 10));
        assertThat(ids(query)).containsExactly(literal.getId());
    }

    @Test void nestedPreviewProjectionKeepsPositionAndChildOwnerFields() {
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

    @Test void privateChildrenDoNotConsumeFiveVisiblePreviewSlots() {
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

    @Test void sortsAscendingDescendingAndNullRankDeterministically() {
        UserList a = list(viewer, "Alpha", UserListVisibility.PUBLIC, 1, 1, null);
        UserList b = list(viewer, "Beta", UserListVisibility.PUBLIC, 2, 2, 1);
        em.flush(); em.clear();
        assertThat(ids(sorted(UserListViewSort.NAME, Sort.Direction.ASC))).containsExactly(a.getId(), b.getId());
        assertThat(ids(sorted(UserListViewSort.NAME, Sort.Direction.DESC))).containsExactly(b.getId(), a.getId());
        assertThat(ids(sorted(UserListViewSort.RANK, Sort.Direction.ASC))).containsExactly(b.getId(), a.getId());
    }

    @Test void aggregateSortsUseDirectItemAndCommentCounts() {
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

    @Test void invalidModeSourceCombinationsAreRejected() {
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
    private void item(UserList parent, Content content, UserList child, int position) {
        items.save(UserListItem.builder().userList(parent).content(content).childList(child).position(position)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
    }
    private void like(User user, UserList list, int minute) {
        likes.save(Like.builder().user(user).list(list).createdAt(LocalDateTime.of(2026, 1, 3, 0, minute)).build());
    }
}
