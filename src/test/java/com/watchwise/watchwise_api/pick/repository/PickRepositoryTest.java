package com.watchwise.watchwise_api.pick.repository;

import com.watchwise.watchwise_api.pick.entity.*;
import com.watchwise.watchwise_api.pickstemplate.entity.*;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.entity.Follower;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userblock.entity.UserBlock;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest @AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) @Testcontainers
class PickRepositoryTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) { r.add("spring.datasource.url", postgres::getJdbcUrl); r.add("spring.datasource.username", postgres::getUsername); r.add("spring.datasource.password", postgres::getPassword); }
    @Autowired PickRepository repository; @Autowired PicksTemplateRepository templateRepository; @Autowired UserRepository userRepository; @Autowired FollowerRepository followerRepository;
    @PersistenceContext EntityManager entityManager;
    @Test void acceptsTwoPicksForSameUserAndTemplate() {
        LocalDateTime now = LocalDateTime.now(); User user = userRepository.saveAndFlush(User.builder().username("lucas").name("lucas").email("lucas@example.com").password("hash").profilePicture("https://example.com/a.png").createdAt(now).updatedAt(now).build());
        PicksTemplate template = templateRepository.saveAndFlush(PicksTemplate.builder().origin(PickOrigin.OFFICIAL).name("Awards").createdAt(now).updatedAt(now).build());
        repository.saveAndFlush(Pick.builder().user(user).picksTemplate(template).visibility(PickVisibility.PUBLIC).createdAt(now).updatedAt(now).build());
        Pick second = repository.saveAndFlush(Pick.builder().user(user).picksTemplate(template).visibility(PickVisibility.PRIVATE).createdAt(now).updatedAt(now).build());
        assertThat(second.getId()).isNotNull();
    }

    @Test
    @DisplayName("[findByIdInWithPicksTemplate] Should Fetch Only Templates For The Requested Picks - When Picks Exist")
    void shouldFetchOnlyTemplatesForTheRequestedPicksWhenPicksExist() {
        LocalDateTime now = LocalDateTime.now();
        User owner = user("batch-pick-owner", now);
        PicksTemplate template = templateRepository.saveAndFlush(PicksTemplate.builder()
                .creator(owner).origin(PickOrigin.COMMUNITY).name("Batch template")
                .createdAt(now).updatedAt(now).build());
        Pick pick = save(owner, template, PickVisibility.PUBLIC, now);
        entityManager.flush();
        entityManager.clear();

        List<Pick> result = repository.findByIdInWithPicksTemplate(List.of(pick.getId()));

        assertThat(result).extracting(Pick::getId).containsExactly(pick.getId());
        assertThat(Hibernate.isInitialized(result.getFirst().getPicksTemplate())).isTrue();
        assertThat(Hibernate.isInitialized(result.getFirst().getUser())).isFalse();
    }

    @Test
    @DisplayName("[findVisibleByUserId] Should Isolate And Order Owned Picks Across Pages - When Another User Has Newer Picks")
    void shouldIsolateAndOrderOwnedPicksAcrossPagesWhenAnotherUserHasNewerPicks() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 12, 12, 0);
        User owner = user("owned-page-owner", now);
        User other = user("owned-page-other", now);
        PicksTemplate template = templateRepository.saveAndFlush(PicksTemplate.builder().origin(PickOrigin.COMMUNITY)
                .name("Owned page").createdAt(now).updatedAt(now).build());
        Pick older = save(owner, template, PickVisibility.PRIVATE, now.minusDays(1));
        Pick newer = save(owner, template, PickVisibility.PUBLIC, now);
        save(other, template, PickVisibility.PUBLIC, now.plusDays(1));

        Page<Pick> firstPage = repository.findVisibleByUserId(owner.getId(), owner.getId(), PageRequest.of(0, 1));
        Page<Pick> secondPage = repository.findVisibleByUserId(owner.getId(), owner.getId(), PageRequest.of(1, 1));

        assertThat(firstPage.getContent()).extracting(Pick::getId).containsExactly(newer.getId());
        assertThat(firstPage.getTotalElements()).isEqualTo(2);
        assertThat(secondPage.getContent()).extracting(Pick::getId).containsExactly(older.getId());
    }

    @Test
    @DisplayName("[findVisibleByUserId] Should Fetch Card To-One Associations With The Page - When Picks Span Templates")
    void shouldFetchCardToOneAssociationsWithPageWhenPicksSpanTemplates() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 12, 12, 0);
        User owner = user("owned-card-page-owner", now);
        User firstCreator = user("owned-card-page-creator-a", now);
        User secondCreator = user("owned-card-page-creator-b", now);
        PicksTemplate firstTemplate = templateRepository.saveAndFlush(PicksTemplate.builder().creator(firstCreator)
                .origin(PickOrigin.COMMUNITY).name("First").createdAt(now).updatedAt(now).build());
        PicksTemplate secondTemplate = templateRepository.saveAndFlush(PicksTemplate.builder().creator(secondCreator)
                .origin(PickOrigin.COMMUNITY).name("Second").createdAt(now).updatedAt(now).build());
        save(owner, firstTemplate, PickVisibility.PRIVATE, now.plusMinutes(1));
        save(owner, secondTemplate, PickVisibility.PRIVATE, now);
        entityManager.flush();
        entityManager.clear();

        Page<Pick> page = repository.findVisibleByUserId(owner.getId(), owner.getId(), PageRequest.of(0, 5));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent()).allSatisfy(pick -> {
            assertThat(Hibernate.isInitialized(pick.getUser())).isTrue();
            assertThat(Hibernate.isInitialized(pick.getPicksTemplate())).isTrue();
            assertThat(Hibernate.isInitialized(pick.getPicksTemplate().getCreator())).isTrue();
        });
    }
    @Test void exposesOwnerPublicAndAcceptedFollowerPicksOnly() {
        LocalDateTime now = LocalDateTime.now();
        User owner = user("owner", now), follower = user("follower", now), stranger = user("stranger", now);
        PicksTemplate template = templateRepository.saveAndFlush(PicksTemplate.builder().origin(PickOrigin.OFFICIAL).name("Visibility").createdAt(now).updatedAt(now).build());
        Pick publicPick = save(owner, template, PickVisibility.PUBLIC, now); Pick followersPick = save(owner, template, PickVisibility.FOLLOWERS, now); save(owner, template, PickVisibility.PRIVATE, now);
        followerRepository.save(Follower.builder().follower(follower).followed(owner).status(FollowStatus.ACCEPTED).createdAt(now).build());
        followerRepository.saveAndFlush(Follower.builder().follower(stranger).followed(owner).status(FollowStatus.PENDING).createdAt(now).build());
        assertThat(repository.findVisibleByOwner(owner.getId(), owner.getId(), template.getId(), PageRequest.of(0, 10)).getTotalElements()).isEqualTo(3);
        assertThat(repository.findVisibleByOwner(follower.getId(), owner.getId(), template.getId(), PageRequest.of(0, 10)).getContent()).extracting(Pick::getId).containsExactlyInAnyOrder(publicPick.getId(), followersPick.getId());
        assertThat(repository.findVisibleByOwner(stranger.getId(), owner.getId(), template.getId(), PageRequest.of(0, 10)).getContent()).extracting(Pick::getId).containsExactly(publicPick.getId());
    }

    @Test
    void findsFeedCandidatesByFollowerVisibilityAndCursor() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 23, 12, 0);
        User owner = user("feed-owner", now);
        User viewer = user("feed-viewer", now);
        PicksTemplate template = templateRepository.saveAndFlush(PicksTemplate.builder()
                .creator(owner).origin(PickOrigin.COMMUNITY).name("Feed template")
                .createdAt(now).updatedAt(now).build());

        Pick newerPublic = save(owner, template, PickVisibility.PUBLIC, now.plusMinutes(3));
        Pick followersPick = save(owner, template, PickVisibility.FOLLOWERS, now.plusMinutes(2));
        Pick olderPublic = save(owner, template, PickVisibility.PUBLIC, now.plusMinutes(1));
        save(owner, template, PickVisibility.PRIVATE, now.plusMinutes(4));
        followerRepository.saveAndFlush(Follower.builder().follower(viewer).followed(owner)
                .status(FollowStatus.ACCEPTED).createdAt(now).build());

        List<Pick> candidates = repository.findFeedCandidates(
                List.of(owner.getId()), viewer.getId(), now.plusMinutes(4), null, PageRequest.of(0, 10));

        assertThat(candidates).extracting(Pick::getId)
                .containsExactly(newerPublic.getId(), followersPick.getId(), olderPublic.getId());
    }

    @Test
    void excludesBlockedPickAuthorsBeforeTemplatePaginationAndCount() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 20, 12, 0);
        User viewer = user("blocked-pick-viewer", now);
        User blockedAuthor = user("blocked-pick-author", now);
        User visibleAuthor = user("visible-pick-author", now);
        PicksTemplate template = templateRepository.saveAndFlush(PicksTemplate.builder()
                .origin(PickOrigin.OFFICIAL).name("Picks").createdAt(now).updatedAt(now).build());
        save(blockedAuthor, template, PickVisibility.PUBLIC, now.plusMinutes(2));
        Pick visiblePick = save(visibleAuthor, template, PickVisibility.PUBLIC, now.plusMinutes(1));
        entityManager.persist(UserBlock.builder().blocker(viewer).blocked(blockedAuthor).createdAt(now).build());
        entityManager.flush();
        entityManager.clear();

        Page<Pick> result = repository.findVisibleByTemplateRecent(
                viewer.getId(), template.getId(), PageRequest.of(0, 1));

        assertThat(result.getContent()).extracting(Pick::getId).containsExactly(visiblePick.getId());
        assertThat(result.getTotalElements()).isEqualTo(1);
    }
    private User user(String value, LocalDateTime now) { return userRepository.save(User.builder().username(value).name(value).email(value + "@example.com").password("hash").profilePicture("https://example.com/a.png").createdAt(now).updatedAt(now).build()); }
    private Pick save(User user, PicksTemplate template, PickVisibility visibility, LocalDateTime now) {
        return save(user, template, visibility, now, 0);
    }

    private Pick save(User user, PicksTemplate template, PickVisibility visibility, LocalDateTime createdAt, int likesCount) {
        return save(null, user, template, visibility, createdAt, likesCount);
    }

    private Pick save(UUID id, User user, PicksTemplate template, PickVisibility visibility,
                      LocalDateTime createdAt, int likesCount) {
        if (id == null) {
            return repository.saveAndFlush(Pick.builder().user(user).picksTemplate(template).visibility(visibility)
                    .createdAt(createdAt).updatedAt(createdAt).likesCount(likesCount).build());
        }
        entityManager.createNativeQuery("""
                        insert into picks (id, picks_template_id, user_id, visibility, created_at, updated_at, likes_count)
                        values (:id, :templateId, :userId, :visibility, :createdAt, :updatedAt, :likesCount)
                        """)
                .setParameter("id", id)
                .setParameter("templateId", template.getId())
                .setParameter("userId", user.getId())
                .setParameter("visibility", visibility.name())
                .setParameter("createdAt", createdAt)
                .setParameter("updatedAt", createdAt)
                .setParameter("likesCount", likesCount)
                .executeUpdate();
        entityManager.flush();
        return repository.findById(id).orElseThrow();
    }

    @Test
    void findsVisibleTemplatePicksByRecentAndPopularOrder() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 18, 12, 0);
        User owner = user("template-rank-owner", now);
        User follower = user("template-rank-follower", now);
        User stranger = user("template-rank-stranger", now);
        PicksTemplate template = templateRepository.saveAndFlush(PicksTemplate.builder()
                .creator(owner).origin(PickOrigin.OFFICIAL).name("Template ranking").createdAt(now).updatedAt(now).build());

        Pick publicPick = save(UUID.fromString("00000000-0000-0000-0000-000000000001"), owner, template,
                PickVisibility.PUBLIC, now.plusHours(2), 1);
        Pick followersPick = save(UUID.fromString("00000000-0000-0000-0000-000000000002"), owner, template,
                PickVisibility.FOLLOWERS, now.plusHours(2), 1);
        Pick privatePick = save(UUID.fromString("00000000-0000-0000-0000-000000000003"), owner, template,
                PickVisibility.PRIVATE, now.plusHours(1), 10);

        followerRepository.saveAndFlush(Follower.builder().follower(follower).followed(owner)
                .status(FollowStatus.ACCEPTED).createdAt(now).build());
        followerRepository.saveAndFlush(Follower.builder().follower(stranger).followed(owner)
                .status(FollowStatus.PENDING).createdAt(now).build());
        entityManager.flush();
        entityManager.clear();

        PageRequest page = PageRequest.of(0, 10);
        assertThat(repository.findVisibleByTemplateRecent(owner.getId(), template.getId(), page).getContent())
                .extracting(Pick::getId)
                .containsExactly(followersPick.getId(), publicPick.getId(), privatePick.getId());
        assertThat(repository.findVisibleByTemplateRecent(follower.getId(), template.getId(), page).getContent())
                .extracting(Pick::getId)
                .containsExactly(followersPick.getId(), publicPick.getId());
        assertThat(repository.findVisibleByTemplateRecent(stranger.getId(), template.getId(), page).getContent())
                .extracting(Pick::getId)
                .containsExactly(publicPick.getId());

        assertThat(repository.findVisibleByTemplatePopular(owner.getId(), template.getId(), page).getContent())
                .extracting(Pick::getId)
                .containsExactly(privatePick.getId(), followersPick.getId(), publicPick.getId());
        assertThat(repository.findVisibleByTemplatePopular(follower.getId(), template.getId(), page).getContent())
                .extracting(Pick::getId)
                .containsExactly(followersPick.getId(), publicPick.getId());
        assertThat(repository.findVisibleByTemplatePopular(stranger.getId(), template.getId(), page).getContent())
                .extracting(Pick::getId)
                .containsExactly(publicPick.getId());

        Page<Pick> visualPage = repository.findVisibleByTemplateRecent(follower.getId(), template.getId(), page);
        assertThat(visualPage.getContent()).allSatisfy(pick -> {
            assertThat(Hibernate.isInitialized(pick.getUser())).isTrue();
            assertThat(Hibernate.isInitialized(pick.getPicksTemplate())).isTrue();
            assertThat(Hibernate.isInitialized(pick.getPicksTemplate().getCreator())).isTrue();
        });
    }

    @Test
    void supportsOwnerTemplatePaginationAndVisibleQueryWithoutTemplateFilterAcrossTwoOwners() {
        LocalDateTime now = LocalDateTime.now();
        User owner = user("page-owner", now), otherOwner = user("page-other", now), viewer = user("page-viewer", now);
        PicksTemplate first = templateRepository.save(PicksTemplate.builder().creator(owner).origin(PickOrigin.OFFICIAL).name("First").createdAt(now).updatedAt(now).build());
        PicksTemplate second = templateRepository.saveAndFlush(PicksTemplate.builder().creator(owner).origin(PickOrigin.OFFICIAL).name("Second").createdAt(now).updatedAt(now).build());
        Pick firstPick = save(owner, first, PickVisibility.PUBLIC, now); Pick secondPick = save(owner, first, PickVisibility.PUBLIC, now); Pick thirdPick = save(owner, first, PickVisibility.PUBLIC, now);
        Pick secondTemplatePick = save(owner, second, PickVisibility.PUBLIC, now); save(otherOwner, first, PickVisibility.PUBLIC, now);
        entityManager.flush();
        entityManager.clear();

        Page<Pick> firstPage = repository.findByUserIdAndPicksTemplateIdOrderByCreatedAtDescIdDesc(owner.getId(), first.getId(), PageRequest.of(0, 2));
        Page<Pick> secondPage = repository.findByUserIdAndPicksTemplateIdOrderByCreatedAtDescIdDesc(owner.getId(), first.getId(), PageRequest.of(1, 2));
        assertThat(firstPage.getTotalElements()).isEqualTo(3); assertThat(firstPage.getTotalPages()).isEqualTo(2); assertThat(firstPage.getContent()).hasSize(2);
        assertThat(secondPage.getTotalElements()).isEqualTo(3); assertThat(secondPage.getTotalPages()).isEqualTo(2); assertThat(secondPage.getContent()).hasSize(1);
        assertThat(Stream.concat(firstPage.getContent().stream(), secondPage.getContent().stream()).map(Pick::getId).toList())
                .containsExactlyInAnyOrder(firstPick.getId(), secondPick.getId(), thirdPick.getId());
        assertThat(firstPage.getContent()).allSatisfy(pick -> {
            assertThat(Hibernate.isInitialized(pick.getUser())).isTrue();
            assertThat(Hibernate.isInitialized(pick.getPicksTemplate())).isTrue();
            assertThat(Hibernate.isInitialized(pick.getPicksTemplate().getCreator())).isTrue();
        });
        Page<Pick> visiblePicks = repository.findVisibleByOwner(viewer.getId(), owner.getId(), null, PageRequest.of(0, 10));
        assertThat(visiblePicks.getTotalElements()).isEqualTo(4);
        assertThat(visiblePicks.getContent()).extracting(Pick::getId)
                .containsExactlyInAnyOrder(firstPick.getId(), secondPick.getId(), thirdPick.getId(), secondTemplatePick.getId());
    }

    @Test
    @DisplayName("[findVisibleByTemplateRecent] Should preserve every Pick across joined pages - When the page is smaller than the result")
    void shouldPreserveEveryPickAcrossJoinedPagesWhenPageIsSmallerThanResult() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 12, 12, 0);
        User owner = user("joined-page-owner", now);
        User viewer = user("joined-page-viewer", now);
        PicksTemplate template = templateRepository.saveAndFlush(PicksTemplate.builder().creator(owner)
                .origin(PickOrigin.COMMUNITY).name("Joined pages").createdAt(now).updatedAt(now).build());
        List<UUID> pickIds = List.of(
                save(owner, template, PickVisibility.PUBLIC, now).getId(),
                save(owner, template, PickVisibility.PUBLIC, now).getId(),
                save(owner, template, PickVisibility.PUBLIC, now).getId(),
                save(owner, template, PickVisibility.PUBLIC, now).getId(),
                save(owner, template, PickVisibility.PUBLIC, now).getId());
        entityManager.flush();
        entityManager.clear();

        Page<Pick> firstPage = repository.findVisibleByTemplateRecent(viewer.getId(), template.getId(), PageRequest.of(0, 2));
        Page<Pick> secondPage = repository.findVisibleByTemplateRecent(viewer.getId(), template.getId(), PageRequest.of(1, 2));
        Page<Pick> thirdPage = repository.findVisibleByTemplateRecent(viewer.getId(), template.getId(), PageRequest.of(2, 2));

        assertThat(firstPage.getTotalElements()).isEqualTo(5);
        assertThat(firstPage.getTotalPages()).isEqualTo(3);
        assertThat(Stream.of(firstPage, secondPage, thirdPage).flatMap(page -> page.getContent().stream())
                .map(Pick::getId).toList()).containsExactlyInAnyOrderElementsOf(pickIds);
    }

    @Test
    @DisplayName("[findByIdWithUserAndTemplateAndCreator] Should fetch detail associations in the same read - When a Pick is loaded")
    void shouldFetchDetailAssociationsInTheSameReadWhenPickIsLoaded() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 12, 12, 0);
        User owner = user("detail-owner", now);
        User creator = user("detail-creator", now);
        PicksTemplate template = templateRepository.saveAndFlush(PicksTemplate.builder().creator(creator)
                .origin(PickOrigin.COMMUNITY).name("Detail").createdAt(now).updatedAt(now).build());
        Pick pick = save(owner, template, PickVisibility.PUBLIC, now);
        entityManager.flush();
        entityManager.clear();

        Pick result = repository.findByIdWithUserAndTemplateAndCreator(pick.getId()).orElseThrow();

        assertThat(Hibernate.isInitialized(result.getUser())).isTrue();
        assertThat(Hibernate.isInitialized(result.getPicksTemplate())).isTrue();
        assertThat(Hibernate.isInitialized(result.getPicksTemplate().getCreator())).isTrue();
        assertThat(result.getUser().getId()).isEqualTo(owner.getId());
        assertThat(result.getPicksTemplate().getCreator().getId()).isEqualTo(creator.getId());
    }

    @Test
    @Transactional
    void locksPickByIdForUpdate() {
        LocalDateTime now = LocalDateTime.now(); User owner = user("pick-lock", now);
        PicksTemplate template = templateRepository.saveAndFlush(PicksTemplate.builder().origin(PickOrigin.OFFICIAL).name("Lock").createdAt(now).updatedAt(now).build());
        Pick pick = save(owner, template, PickVisibility.PRIVATE, now);
        assertThat(repository.findByIdForUpdate(pick.getId())).map(Pick::getId).contains(pick.getId());
    }
}
