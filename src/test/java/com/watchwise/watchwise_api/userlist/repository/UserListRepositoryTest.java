package com.watchwise.watchwise_api.userlist.repository;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListItem;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class UserListRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private UserListRepository userListRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ContentRepository contentRepository;

    @Autowired
    private com.watchwise.watchwise_api.userlist.repository.UserListItemRepository userListItemRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private User lucas;
    private User marina;

    @BeforeEach
    void setUp() {
        userListItemRepository.deleteAll();
        userListRepository.deleteAll();
        contentRepository.deleteAll();
        userRepository.deleteAll();

        lucas = userRepository.save(buildUser("lucas", "lucas@email.com"));
        marina = userRepository.save(buildUser("marina", "marina@email.com"));
    }

    @Test
    @DisplayName("[findByUserId] Should Return The User's Lists - When They Exist")
    void shouldReturnTheUsersListsWhenTheyExist() {
        UserList scifi = userListRepository.save(buildList(lucas, "Best sci-fi of the 90s", true));
        UserList horror = userListRepository.saveAndFlush(buildList(lucas, "Underrated horror", false));
        entityManager.clear();

        List<UserList> result = userListRepository.findByUserId(lucas.getId());

        assertThat(result).extracting(UserList::getName).containsExactlyInAnyOrder("Best sci-fi of the 90s", "Underrated horror");
        assertThat(result).extracting(UserList::getId).containsExactlyInAnyOrder(scifi.getId(), horror.getId());
    }

    @Test
    @DisplayName("[findByUserId] Should Return Empty List - When User Has No Lists")
    void shouldReturnEmptyListWhenUserHasNoLists() {
        List<UserList> result = userListRepository.findByUserId(lucas.getId());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[findByUserId] Should Not Include Lists Of A Different User - When Filtering")
    void shouldNotIncludeListsOfADifferentUserWhenFiltering() {
        userListRepository.save(buildList(lucas, "Lucas' list", true));
        userListRepository.saveAndFlush(buildList(marina, "Marina's list", true));
        entityManager.clear();

        List<UserList> result = userListRepository.findByUserId(lucas.getId());

        assertThat(result).extracting(UserList::getName).containsExactly("Lucas' list");
    }

    @Test
    @DisplayName("[findByUserId] Should Return Requested Page - When Paginated")
    void shouldReturnRequestedPageWhenPaginated() {
        userListRepository.save(buildList(lucas, "List 1", true));
        userListRepository.saveAndFlush(buildList(lucas, "List 2", true));
        entityManager.clear();

        Page<UserList> firstPage = userListRepository.findByUserId(lucas.getId(), PageRequest.of(0, 1));

        assertThat(firstPage.getTotalElements()).isEqualTo(2);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("[findByUserIdAndVisibilityIn] Should Only Return Lists Matching The Given Visibilities - When Filtering")
    void shouldOnlyReturnListsMatchingTheGivenVisibilitiesWhenFiltering() {
        userListRepository.save(buildListWithVisibility(lucas, "Public list", UserListVisibility.PUBLIC));
        userListRepository.save(buildListWithVisibility(lucas, "Followers-only list", UserListVisibility.FOLLOWERS));
        userListRepository.saveAndFlush(buildListWithVisibility(lucas, "Private list", UserListVisibility.PRIVATE));
        entityManager.clear();

        Page<UserList> result = userListRepository.findByUserIdAndVisibilityIn(
                lucas.getId(), List.of(UserListVisibility.PUBLIC, UserListVisibility.FOLLOWERS), PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(UserList::getName)
                .containsExactlyInAnyOrder("Public list", "Followers-only list");
    }

    @Test
    @DisplayName("[findByUserIdAndVisibilityIn] Should Return Empty Page - When User Has No List Matching The Given Visibilities")
    void shouldReturnEmptyPageWhenUserHasNoListMatchingTheGivenVisibilities() {
        userListRepository.saveAndFlush(buildListWithVisibility(lucas, "Private list", UserListVisibility.PRIVATE));
        entityManager.clear();

        Page<UserList> result = userListRepository.findByUserIdAndVisibilityIn(
                lucas.getId(), List.of(UserListVisibility.PUBLIC), PageRequest.of(0, 10));

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("[save] Should Persist The Description - When Provided")
    void shouldPersistTheDescriptionWhenProvided() {
        UserList saved = userListRepository.saveAndFlush(buildListWithDescription(lucas, "My list", "A curated list", true));
        entityManager.clear();

        UserList found = userListRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getDescription()).isEqualTo("A curated list");
    }

    @Test
    @DisplayName("[save] Should Allow A Null Description")
    void shouldAllowANullDescription() {
        UserList saved = userListRepository.saveAndFlush(buildList(lucas, "My list", true));
        entityManager.clear();

        UserList found = userListRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getDescription()).isNull();
    }

    @Test
    @DisplayName("[save] Should Persist Visibility As Private - When Explicitly Set")
    void shouldPersistVisibilityAsPrivateWhenExplicitlySet() {
        UserList saved = userListRepository.saveAndFlush(buildListWithVisibility(lucas, "Private list", UserListVisibility.PRIVATE));
        entityManager.clear();

        UserList found = userListRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getVisibility()).isEqualTo(UserListVisibility.PRIVATE);
    }

    @Test
    @DisplayName("[save] Should Persist Visibility As Followers - When Explicitly Set")
    void shouldPersistVisibilityAsFollowersWhenExplicitlySet() {
        UserList saved = userListRepository.saveAndFlush(buildListWithVisibility(lucas, "Followers-only list", UserListVisibility.FOLLOWERS));
        entityManager.clear();

        UserList found = userListRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getVisibility()).isEqualTo(UserListVisibility.FOLLOWERS);
    }

    @Test
    @DisplayName("[save] Should Allow The Same Name Twice For The Same User")
    void shouldAllowTheSameNameTwiceForTheSameUser() {
        userListRepository.saveAndFlush(buildList(lucas, "Duplicate name", true));
        entityManager.clear();

        UserList saved = userListRepository.saveAndFlush(buildList(lucas, "Duplicate name", true));

        assertThat(saved.getId()).isNotNull();
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When Name Is Null")
    void shouldThrowDataIntegrityViolationExceptionWhenNameIsNull() {
        UserList list = buildList(lucas, null, true);

        assertThatThrownBy(() -> userListRepository.saveAndFlush(list))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[deleteAll] Should Cascade Delete UserList Rows - When The User Is Deleted")
    void shouldCascadeDeleteUserListRowsWhenTheUserIsDeleted() {
        UserList saved = userListRepository.saveAndFlush(buildList(lucas, "My list", true));
        entityManager.clear();

        userRepository.delete(userRepository.findById(lucas.getId()).orElseThrow());
        userRepository.flush();

        assertThat(userListRepository.findById(saved.getId())).isEmpty();
    }

    @Test
    @DisplayName("[countByUserIdAndRankIsNotNull] Should Count Only Ranked Lists")
    void shouldCountOnlyRankedLists() {
        UserList ranked = buildList(lucas, "Ranked", true);
        ranked.setRank(1);
        userListRepository.save(ranked);
        userListRepository.saveAndFlush(buildList(lucas, "Unranked", true));
        entityManager.clear();

        assertThat(userListRepository.countByUserIdAndRankIsNotNull(lucas.getId())).isEqualTo(1L);
    }

    @Test
    @DisplayName("[parkRanksInRange][settleParkedRanks] Should Shift Ranks Forward - When Moving A List Backward")
    void shouldShiftRanksForwardWhenMovingAListBackward() {
        UserList first = buildRankedList(lucas, "First", 1);
        UserList second = buildRankedList(lucas, "Second", 2);
        UserList third = buildRankedList(lucas, "Third", 3);
        userListRepository.save(first);
        userListRepository.save(second);
        userListRepository.saveAndFlush(third);
        entityManager.clear();

        userListRepository.parkRanksInRange(lucas.getId(), 2, 3, 1_000_000_000);
        userListRepository.settleParkedRanks(lucas.getId(), 1_000_000_000, -1);
        entityManager.clear();

        assertThat(userListRepository.findById(second.getId()).orElseThrow().getRank()).isEqualTo(1);
        assertThat(userListRepository.findById(third.getId()).orElseThrow().getRank()).isEqualTo(2);
    }

    @Test
    @DisplayName("[findByUserIdOrderByItemsCount] Should Order Lists By Item Count Ascending")
    void shouldOrderListsByItemCountAscending() {
        UserList fewItems = userListRepository.save(buildList(lucas, "Few items", true));
        UserList manyItems = userListRepository.saveAndFlush(buildList(lucas, "Many items", true));
        Content movie = contentRepository.save(Content.builder()
                .tmdbId("550").type(ContentType.MOVIE).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        Content anotherMovie = contentRepository.save(Content.builder()
                .tmdbId("680").type(ContentType.MOVIE).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        userListItemRepository.save(buildContentItem(fewItems, movie, 1));
        userListItemRepository.save(buildContentItem(manyItems, movie, 1));
        userListItemRepository.saveAndFlush(buildContentItem(manyItems, anotherMovie, 2));
        entityManager.clear();

        Page<UserList> result = userListRepository.findByUserIdOrderByItemsCount(
                lucas.getId(), List.of("PUBLIC", "FOLLOWERS", "PRIVATE"), "ASC", PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(UserList::getId).containsExactly(fewItems.getId(), manyItems.getId());
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    private UserListItem buildContentItem(UserList userList, Content content, int position) {
        LocalDateTime now = LocalDateTime.now();
        return UserListItem.builder()
                .userList(userList)
                .content(content)
                .position(position)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private UserList buildRankedList(User user, String name, int rank) {
        UserList list = buildList(user, name, true);
        list.setRank(rank);
        return list;
    }

    @Test
    @DisplayName("[incrementLikesCount] Should Increase LikesCount By One - When Called")
    void shouldIncreaseLikesCountByOneWhenIncrementLikesCountIsCalled() {
        UserList saved = userListRepository.saveAndFlush(buildList(lucas, "My list", true));
        entityManager.clear();

        userListRepository.incrementLikesCount(saved.getId());
        entityManager.clear();

        assertThat(userListRepository.findById(saved.getId()).orElseThrow().getLikesCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("[decrementLikesCount] Should Not Go Below Zero - When Already Zero")
    void shouldNotGoBelowZeroWhenAlreadyZero() {
        UserList saved = userListRepository.saveAndFlush(buildList(lucas, "My list", true));
        entityManager.clear();

        userListRepository.decrementLikesCount(saved.getId());
        entityManager.clear();

        assertThat(userListRepository.findById(saved.getId()).orElseThrow().getLikesCount()).isEqualTo(0);
    }

    private UserList buildList(User user, String name, boolean isPublic) {
        return buildListWithVisibility(user, name, isPublic ? UserListVisibility.PUBLIC : UserListVisibility.PRIVATE);
    }

    private UserList buildListWithVisibility(User user, String name, UserListVisibility visibility) {
        LocalDateTime now = LocalDateTime.now();
        return UserList.builder()
                .user(user)
                .name(name)
                .visibility(visibility)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private UserList buildListWithDescription(User user, String name, String description, boolean isPublic) {
        LocalDateTime now = LocalDateTime.now();
        return UserList.builder()
                .user(user)
                .name(name)
                .description(description)
                .visibility(isPublic ? UserListVisibility.PUBLIC : UserListVisibility.PRIVATE)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private User buildUser(String username, String email) {
        return User.builder()
                .username(username)
                .email(email)
                .password("hashed_password")
                .profilePicture("https://example.com/photo.png")
                .isProfilePublic(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}