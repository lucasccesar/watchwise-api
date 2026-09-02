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
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class UserListItemRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private UserListItemRepository userListItemRepository;

    @Autowired
    private UserListRepository userListRepository;

    @Autowired
    private ContentRepository contentRepository;

    @Autowired
    private UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private User lucas;
    private UserList scifi;
    private UserList nestedList;
    private Content fightClub;
    private Content pulpFiction;

    @BeforeEach
    void setUp() {
        userListItemRepository.deleteAll();
        userListRepository.deleteAll();
        contentRepository.deleteAll();
        userRepository.deleteAll();

        lucas = userRepository.save(buildUser("lucas", "lucas@email.com"));
        scifi = userListRepository.save(buildList(lucas, "Best sci-fi of the 90s"));
        nestedList = userListRepository.save(buildList(lucas, "List of lists"));
        fightClub = contentRepository.save(buildContent("550", ContentType.MOVIE));
        pulpFiction = contentRepository.save(buildContent("680", ContentType.MOVIE));
    }

    @Test
    @DisplayName("[findByUserListIdOrderByPositionAsc] Should Return Items Ordered By Position - When Multiple Items Exist")
    void shouldReturnItemsOrderedByPositionWhenMultipleItemsExist() {
        userListItemRepository.save(buildContentItem(scifi, fightClub, 2));
        userListItemRepository.saveAndFlush(buildContentItem(scifi, pulpFiction, 1));
        entityManager.clear();

        List<UserListItem> result = userListItemRepository.findByUserListIdOrderByPositionAsc(scifi.getId());

        assertThat(result).extracting(UserListItem::getPosition).containsExactly(1, 2);
        assertThat(result).extracting(item -> item.getContent().getId())
                .containsExactly(pulpFiction.getId(), fightClub.getId());
    }

    @Test
    @DisplayName("[findByUserListIdOrderByPositionAsc] Should Return Empty List - When List Has No Items")
    void shouldReturnEmptyListWhenListHasNoItems() {
        List<UserListItem> result = userListItemRepository.findByUserListIdOrderByPositionAsc(scifi.getId());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[findByUserListIdOrderByPositionAsc] Should Not Include Items Of A Different List - When Filtering")
    void shouldNotIncludeItemsOfADifferentListWhenFiltering() {
        UserList horror = userListRepository.save(buildList(lucas, "Underrated horror"));
        userListItemRepository.save(buildContentItem(scifi, fightClub, 1));
        userListItemRepository.saveAndFlush(buildContentItem(horror, pulpFiction, 1));
        entityManager.clear();

        List<UserListItem> result = userListItemRepository.findByUserListIdOrderByPositionAsc(scifi.getId());

        assertThat(result).extracting(item -> item.getContent().getId()).containsExactly(fightClub.getId());
    }

    @Test
    @DisplayName("[findByUserListIdWithContentAndChildListOrderByPositionAsc] Should Return Items Ordered By Position With Content And Child List Already Initialized - When Multiple Items Exist")
    void shouldReturnItemsOrderedByPositionWithContentAndChildListAlreadyInitializedWhenMultipleItemsExist() {
        userListItemRepository.save(buildContentItem(scifi, fightClub, 1));
        userListItemRepository.saveAndFlush(buildChildListItem(scifi, nestedList, 2));
        entityManager.clear();

        List<UserListItem> result = userListItemRepository
                .findByUserListIdWithContentAndChildListOrderByPositionAsc(scifi.getId());

        assertThat(result).extracting(UserListItem::getPosition).containsExactly(1, 2);
        UserListItem contentItem = result.get(0);
        UserListItem childListItem = result.get(1);
        assertThat(Hibernate.isInitialized(contentItem.getContent())).isTrue();
        assertThat(contentItem.getContent().getId()).isEqualTo(fightClub.getId());
        assertThat(Hibernate.isInitialized(childListItem.getChildList())).isTrue();
        assertThat(childListItem.getChildList().getId()).isEqualTo(nestedList.getId());
        assertThat(Hibernate.isInitialized(childListItem.getChildList().getUser())).isTrue();
        assertThat(childListItem.getChildList().getUser().getId()).isEqualTo(lucas.getId());
    }

    @Test
    @DisplayName("[findByUserListIdWithContentAndChildListOrderByPositionAsc] Should Return Empty List - When List Has No Items")
    void shouldReturnEmptyListWhenListHasNoItemsWithFetchJoin() {
        List<UserListItem> result = userListItemRepository
                .findByUserListIdWithContentAndChildListOrderByPositionAsc(scifi.getId());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[findByUserListIdWithContentAndChildListOrderByPositionAsc] Should Not Include Items Of A Different List - When Filtering")
    void shouldNotIncludeItemsOfADifferentListWhenFilteringWithFetchJoin() {
        UserList horror = userListRepository.save(buildList(lucas, "Underrated horror"));
        userListItemRepository.save(buildContentItem(scifi, fightClub, 1));
        userListItemRepository.saveAndFlush(buildContentItem(horror, pulpFiction, 1));
        entityManager.clear();

        List<UserListItem> result = userListItemRepository
                .findByUserListIdWithContentAndChildListOrderByPositionAsc(scifi.getId());

        assertThat(result).extracting(item -> item.getContent().getId()).containsExactly(fightClub.getId());
    }

    @Test
    @DisplayName("[save] Should Persist A Content Item - When Content Is Provided")
    void shouldPersistAContentItemWhenContentIsProvided() {
        UserListItem saved = userListItemRepository.saveAndFlush(buildContentItem(scifi, fightClub, 1));
        entityManager.clear();

        UserListItem found = userListItemRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getContent().getId()).isEqualTo(fightClub.getId());
        assertThat(found.getChildList()).isNull();
    }

    @Test
    @DisplayName("[save] Should Persist A Nested List Item - When Child List Is Provided")
    void shouldPersistANestedListItemWhenChildListIsProvided() {
        UserListItem saved = userListItemRepository.saveAndFlush(buildChildListItem(nestedList, scifi, 1));
        entityManager.clear();

        UserListItem found = userListItemRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getChildList().getId()).isEqualTo(scifi.getId());
        assertThat(found.getContent()).isNull();
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When Neither Content Nor Child List Is Provided")
    void shouldThrowDataIntegrityViolationExceptionWhenNeitherContentNorChildListIsProvided() {
        UserListItem item = UserListItem.builder()
                .userList(scifi)
                .position(1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        assertThatThrownBy(() -> userListItemRepository.saveAndFlush(item))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When Both Content And Child List Are Provided")
    void shouldThrowDataIntegrityViolationExceptionWhenBothContentAndChildListAreProvided() {
        UserListItem item = UserListItem.builder()
                .userList(nestedList)
                .content(fightClub)
                .childList(scifi)
                .position(1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        assertThatThrownBy(() -> userListItemRepository.saveAndFlush(item))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When Child List Is The Item's Own List")
    void shouldThrowDataIntegrityViolationExceptionWhenChildListIsTheItemsOwnList() {
        UserListItem item = buildChildListItem(nestedList, nestedList, 1);

        assertThatThrownBy(() -> userListItemRepository.saveAndFlush(item))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When Position Is Below Minimum")
    void shouldThrowDataIntegrityViolationExceptionWhenPositionIsBelowMinimum() {
        assertThatThrownBy(() -> userListItemRepository.saveAndFlush(buildContentItem(scifi, fightClub, 0)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When Position Is Already Taken For That List")
    void shouldThrowDataIntegrityViolationExceptionWhenPositionIsAlreadyTakenForThatList() {
        userListItemRepository.saveAndFlush(buildContentItem(scifi, fightClub, 1));
        entityManager.clear();

        assertThatThrownBy(() -> userListItemRepository.saveAndFlush(buildContentItem(scifi, pulpFiction, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[save] Should Allow Same Position For Different Lists")
    void shouldAllowSamePositionForDifferentLists() {
        UserList horror = userListRepository.save(buildList(lucas, "Underrated horror"));
        userListItemRepository.saveAndFlush(buildContentItem(scifi, fightClub, 1));
        entityManager.clear();

        UserListItem saved = userListItemRepository.saveAndFlush(buildContentItem(horror, pulpFiction, 1));

        assertThat(saved.getId()).isNotNull();
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When Content Is Already In That List")
    void shouldThrowDataIntegrityViolationExceptionWhenContentIsAlreadyInThatList() {
        userListItemRepository.saveAndFlush(buildContentItem(scifi, fightClub, 1));
        entityManager.clear();

        assertThatThrownBy(() -> userListItemRepository.saveAndFlush(buildContentItem(scifi, fightClub, 2)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[existsByUserListIdAndContentIdIsNotNull] Should Return True - When List Has A Content Item")
    void shouldReturnTrueWhenListHasAContentItem() {
        userListItemRepository.saveAndFlush(buildContentItem(scifi, fightClub, 1));
        entityManager.clear();

        assertThat(userListItemRepository.existsByUserListIdAndContentIdIsNotNull(scifi.getId())).isTrue();
    }

    @Test
    @DisplayName("[existsByUserListIdAndContentIdIsNotNull] Should Return False - When List Has No Content Item")
    void shouldReturnFalseWhenListHasNoContentItem() {
        userListItemRepository.saveAndFlush(buildChildListItem(nestedList, scifi, 1));
        entityManager.clear();

        assertThat(userListItemRepository.existsByUserListIdAndContentIdIsNotNull(nestedList.getId())).isFalse();
    }

    @Test
    @DisplayName("[existsByUserListIdAndChildListIdIsNotNull] Should Return True - When List Has A Nested List Item")
    void shouldReturnTrueWhenListHasANestedListItem() {
        userListItemRepository.saveAndFlush(buildChildListItem(nestedList, scifi, 1));
        entityManager.clear();

        assertThat(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(nestedList.getId())).isTrue();
    }

    @Test
    @DisplayName("[existsByUserListIdAndChildListIdIsNotNull] Should Return False - When List Has No Nested List Item")
    void shouldReturnFalseWhenListHasNoNestedListItem() {
        userListItemRepository.saveAndFlush(buildContentItem(scifi, fightClub, 1));
        entityManager.clear();

        assertThat(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(scifi.getId())).isFalse();
    }

    @Test
    @DisplayName("[findContentItemsByUserListIdInOrderByPosition] Should Return Content Items Ordered By List Then Position - When Multiple Lists Are Requested")
    void shouldReturnContentItemsOrderedByListThenPositionWhenMultipleListsAreRequested() {
        UserList horror = userListRepository.save(buildList(lucas, "Underrated horror"));
        userListItemRepository.save(buildContentItem(scifi, fightClub, 2));
        userListItemRepository.save(buildContentItem(scifi, pulpFiction, 1));
        userListItemRepository.saveAndFlush(buildContentItem(horror, fightClub, 1));
        entityManager.clear();

        List<UserListItem> result = userListItemRepository.findContentItemsByUserListIdInOrderByPosition(
                List.of(scifi.getId(), horror.getId()));

        assertThat(result).hasSize(3);
        assertThat(result).filteredOn(item -> item.getUserList().getId().equals(scifi.getId()))
                .extracting(UserListItem::getPosition).containsExactly(1, 2);
        assertThat(result).filteredOn(item -> item.getUserList().getId().equals(horror.getId()))
                .extracting(item -> item.getContent().getId()).containsExactly(fightClub.getId());
    }

    @Test
    @DisplayName("[findContentItemsByUserListIdInOrderByPosition] Should Not Include Nested List Items")
    void shouldNotIncludeNestedListItemsWhenFindingContentItemsByListIds() {
        userListItemRepository.saveAndFlush(buildChildListItem(nestedList, scifi, 1));
        entityManager.clear();

        List<UserListItem> result = userListItemRepository.findContentItemsByUserListIdInOrderByPosition(
                List.of(nestedList.getId()));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[findContentItemsByUserListIdInOrderByPosition] Should Return Empty List - When None Of The Lists Have Items")
    void shouldReturnEmptyListWhenNoneOfTheListsHaveItems() {
        List<UserListItem> result = userListItemRepository.findContentItemsByUserListIdInOrderByPosition(
                List.of(scifi.getId()));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[countNestedListsByUserListIdIn] Should Count Only Nested List Items Per List - When Several Lists Are Requested")
    void shouldCountOnlyNestedListItemsPerListWhenSeveralListsAreRequested() {
        UserList anotherNestedList = userListRepository.save(buildList(lucas, "Another list of lists"));
        userListItemRepository.save(buildChildListItem(nestedList, scifi, 1));
        UserList horror = userListRepository.save(buildList(lucas, "Underrated horror"));
        userListItemRepository.saveAndFlush(buildChildListItem(nestedList, horror, 2));
        entityManager.clear();

        List<UserListItemRepository.UserListCount> result = userListItemRepository.countNestedListsByUserListIdIn(
                List.of(nestedList.getId(), anotherNestedList.getId()));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getUserListId()).isEqualTo(nestedList.getId());
        assertThat(result.get(0).getCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("[countNestedListsByUserListIdIn] Should Return Empty List - When None Of The Lists Have Nested List Items")
    void shouldReturnEmptyListWhenNoneOfTheListsHaveNestedListItems() {
        userListItemRepository.saveAndFlush(buildContentItem(scifi, fightClub, 1));
        entityManager.clear();

        List<UserListItemRepository.UserListCount> result = userListItemRepository.countNestedListsByUserListIdIn(
                List.of(scifi.getId()));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[countContentItemsByUserListIdIn] Should Count Only Content Items Per List - When Several Lists Are Requested")
    void shouldCountOnlyContentItemsPerListWhenSeveralListsAreRequested() {
        UserList horror = userListRepository.save(buildList(lucas, "Underrated horror"));
        userListItemRepository.save(buildContentItem(scifi, fightClub, 1));
        userListItemRepository.save(buildContentItem(scifi, pulpFiction, 2));
        userListItemRepository.saveAndFlush(buildChildListItem(nestedList, horror, 1));
        entityManager.clear();

        List<UserListItemRepository.UserListCount> result = userListItemRepository.countContentItemsByUserListIdIn(
                List.of(scifi.getId(), nestedList.getId()));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getUserListId()).isEqualTo(scifi.getId());
        assertThat(result.get(0).getCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("[findUserListIdsContainingContent] Should Return Only Lists That Have The Content - When Several Lists Are Requested")
    void shouldReturnOnlyListsThatHaveTheContentWhenSeveralListsAreRequested() {
        UserList horror = userListRepository.save(buildList(lucas, "Underrated horror"));
        userListItemRepository.save(buildContentItem(scifi, fightClub, 1));
        userListItemRepository.saveAndFlush(buildContentItem(horror, pulpFiction, 1));
        entityManager.clear();

        Set<UUID> result = userListItemRepository.findUserListIdsContainingContent(
                List.of(scifi.getId(), horror.getId()), fightClub.getId());

        assertThat(result).containsExactly(scifi.getId());
    }

    @Test
    @DisplayName("[countAllItemsByUserListIdIn] Should Count Content And Nested List Items Together - When Several Lists Are Requested")
    void shouldCountContentAndNestedListItemsTogetherWhenSeveralListsAreRequested() {
        UserList horror = userListRepository.save(buildList(lucas, "Underrated horror"));
        userListItemRepository.save(buildContentItem(scifi, fightClub, 1));
        userListItemRepository.saveAndFlush(buildChildListItem(scifi, horror, 2));
        entityManager.clear();

        List<UserListItemRepository.UserListCount> result = userListItemRepository.countAllItemsByUserListIdIn(List.of(scifi.getId()));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getUserListId()).isEqualTo(scifi.getId());
        assertThat(result.get(0).getCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("[sumRuntimeMinutesByUserListIdIn] Should Sum Only Content Items With RuntimeMinutes - When Several Lists Are Requested")
    void shouldSumOnlyContentItemsWithRuntimeMinutesWhenSeveralListsAreRequested() {
        Content withRuntime = contentRepository.save(buildContent("770", ContentType.MOVIE, 90));
        Content withoutRuntime = contentRepository.save(buildContent("880", ContentType.MOVIE, null));
        UserList horror = userListRepository.save(buildList(lucas, "Underrated horror"));
        userListItemRepository.save(buildContentItem(scifi, withRuntime, 1));
        userListItemRepository.save(buildContentItem(scifi, withoutRuntime, 2));
        userListItemRepository.saveAndFlush(buildChildListItem(nestedList, horror, 1));
        entityManager.clear();

        List<UserListItemRepository.UserListSum> result = userListItemRepository.sumRuntimeMinutesByUserListIdIn(
                List.of(scifi.getId(), nestedList.getId()));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getUserListId()).isEqualTo(scifi.getId());
        assertThat(result.get(0).getTotal()).isEqualTo(90L);
    }

    @Test
    @DisplayName("[sumRuntimeMinutesByUserListIdIn] Should Return Empty List - When No List Has Content Items")
    void shouldReturnEmptyListWhenNoListHasContentItems() {
        UserList horror = userListRepository.save(buildList(lucas, "Underrated horror"));
        userListItemRepository.saveAndFlush(buildChildListItem(nestedList, horror, 1));
        entityManager.clear();

        List<UserListItemRepository.UserListSum> result = userListItemRepository.sumRuntimeMinutesByUserListIdIn(List.of(nestedList.getId()));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[deleteAll] Should Cascade Delete UserListItem Rows - When The Owning List Is Deleted")
    void shouldCascadeDeleteUserListItemRowsWhenTheOwningListIsDeleted() {
        UserListItem saved = userListItemRepository.saveAndFlush(buildContentItem(scifi, fightClub, 1));
        entityManager.clear();

        userListRepository.delete(userListRepository.findById(scifi.getId()).orElseThrow());
        userListRepository.flush();

        assertThat(userListItemRepository.findById(saved.getId())).isEmpty();
    }

    @Test
    @DisplayName("[deleteAll] Should Cascade Delete UserListItem Rows - When The Content Is Deleted")
    void shouldCascadeDeleteUserListItemRowsWhenTheContentIsDeleted() {
        UserListItem saved = userListItemRepository.saveAndFlush(buildContentItem(scifi, fightClub, 1));
        entityManager.clear();

        contentRepository.delete(contentRepository.findById(fightClub.getId()).orElseThrow());
        contentRepository.flush();

        assertThat(userListItemRepository.findById(saved.getId())).isEmpty();
    }

    @Test
    @DisplayName("[deleteAll] Should Cascade Delete UserListItem Rows - When The Child List Is Deleted")
    void shouldCascadeDeleteUserListItemRowsWhenTheChildListIsDeleted() {
        UserListItem saved = userListItemRepository.saveAndFlush(buildChildListItem(nestedList, scifi, 1));
        entityManager.clear();

        userListRepository.delete(userListRepository.findById(scifi.getId()).orElseThrow());
        userListRepository.flush();

        assertThat(userListItemRepository.findById(saved.getId())).isEmpty();
    }

    @Test
    @DisplayName("[findDistinctContentTypesByUserListId] Should Return Every Distinct Type Present - When List Has Items Of More Than One Type")
    void shouldReturnEveryDistinctTypePresentWhenListHasItemsOfMoreThanOneType() {
        Content episode = contentRepository.save(buildEpisodeContent("1396", 1, 1));
        userListItemRepository.save(buildContentItem(scifi, fightClub, 1));
        userListItemRepository.saveAndFlush(buildContentItem(scifi, episode, 2));
        entityManager.clear();

        Set<ContentType> result = userListItemRepository.findDistinctContentTypesByUserListId(scifi.getId());

        assertThat(result).containsExactlyInAnyOrder(ContentType.MOVIE, ContentType.EPISODE);
    }

    @Test
    @DisplayName("[findDistinctContentTypesByUserListId] Should Return Empty Set - When List Has No Content Items")
    void shouldReturnEmptySetWhenListHasNoContentItemsForDistinctTypes() {
        Set<ContentType> result = userListItemRepository.findDistinctContentTypesByUserListId(scifi.getId());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[findDistinctContentTypesByUserListId] Should Ignore ChildList Items - When List Is A List Of Lists")
    void shouldIgnoreChildListItemsWhenListIsAListOfLists() {
        userListItemRepository.saveAndFlush(buildChildListItem(scifi, nestedList, 1));
        entityManager.clear();

        Set<ContentType> result = userListItemRepository.findDistinctContentTypesByUserListId(scifi.getId());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[findDistinctContentTypesByUserListIdIn] Should Group Types By Their Own List - When Multiple Lists Are Requested")
    void shouldGroupTypesByTheirOwnListWhenMultipleListsAreRequestedForDistinctTypes() {
        Content episode = contentRepository.save(buildEpisodeContent("1396", 1, 1));
        UserList horror = userListRepository.save(buildList(lucas, "Underrated horror"));
        userListItemRepository.save(buildContentItem(scifi, fightClub, 1));
        userListItemRepository.saveAndFlush(buildContentItem(horror, episode, 1));
        entityManager.clear();

        List<UserListItemRepository.UserListContentType> result = userListItemRepository
                .findDistinctContentTypesByUserListIdIn(List.of(scifi.getId(), horror.getId()));

        assertThat(result).extracting(
                UserListItemRepository.UserListContentType::getUserListId,
                UserListItemRepository.UserListContentType::getType)
                .containsExactlyInAnyOrder(
                        tuple(scifi.getId(), ContentType.MOVIE),
                        tuple(horror.getId(), ContentType.EPISODE));
    }

    private UserListItem buildContentItem(UserList userList, Content content, Integer position) {
        LocalDateTime now = LocalDateTime.now();
        return UserListItem.builder()
                .userList(userList)
                .content(content)
                .position(position)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private UserListItem buildChildListItem(UserList userList, UserList childList, Integer position) {
        LocalDateTime now = LocalDateTime.now();
        return UserListItem.builder()
                .userList(userList)
                .childList(childList)
                .position(position)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private UserList buildList(User user, String name) {
        LocalDateTime now = LocalDateTime.now();
        return UserList.builder()
                .user(user)
                .name(name)
                .visibility(UserListVisibility.PUBLIC)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private Content buildContent(String tmdbId, ContentType type, Integer runtimeMinutes) {
        LocalDateTime now = LocalDateTime.now();
        return Content.builder()
                .tmdbId(tmdbId)
                .type(type)
                .runtimeMinutes(runtimeMinutes)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private Content buildContent(String tmdbId, ContentType type) {
        LocalDateTime now = LocalDateTime.now();
        return Content.builder()
                .tmdbId(tmdbId)
                .type(type)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private Content buildEpisodeContent(String seriesTmdbId, Integer seasonNumber, Integer episodeNumber) {
        LocalDateTime now = LocalDateTime.now();
        return Content.builder()
                .type(ContentType.EPISODE)
                .seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber)
                .episodeNumber(episodeNumber)
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
