package com.watchwise.watchwise_api.userlist.repository;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListItem;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                .isPublic(true)
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
