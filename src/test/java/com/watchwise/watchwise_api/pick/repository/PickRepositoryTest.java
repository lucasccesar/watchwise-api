package com.watchwise.watchwise_api.pick.repository;

import com.watchwise.watchwise_api.pick.entity.*;
import com.watchwise.watchwise_api.pickstemplate.entity.*;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.entity.Follower;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
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
        LocalDateTime now = LocalDateTime.now(); User user = userRepository.saveAndFlush(User.builder().username("lucas").email("lucas@example.com").password("hash").profilePicture("https://example.com/a.png").createdAt(now).updatedAt(now).build());
        PicksTemplate template = templateRepository.saveAndFlush(PicksTemplate.builder().origin(PickOrigin.OFFICIAL).name("Awards").createdAt(now).updatedAt(now).build());
        repository.saveAndFlush(Pick.builder().user(user).picksTemplate(template).visibility(PickVisibility.PUBLIC).createdAt(now).updatedAt(now).build());
        Pick second = repository.saveAndFlush(Pick.builder().user(user).picksTemplate(template).visibility(PickVisibility.PRIVATE).createdAt(now).updatedAt(now).build());
        assertThat(second.getId()).isNotNull();
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
    private User user(String value, LocalDateTime now) { return userRepository.save(User.builder().username(value).email(value + "@example.com").password("hash").profilePicture("https://example.com/a.png").createdAt(now).updatedAt(now).build()); }
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
                .origin(PickOrigin.OFFICIAL).name("Template ranking").createdAt(now).updatedAt(now).build());

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
    }

    @Test
    void supportsOwnerTemplatePaginationAndVisibleQueryWithoutTemplateFilterAcrossTwoOwners() {
        LocalDateTime now = LocalDateTime.now();
        User owner = user("page-owner", now), otherOwner = user("page-other", now), viewer = user("page-viewer", now);
        PicksTemplate first = templateRepository.save(PicksTemplate.builder().origin(PickOrigin.OFFICIAL).name("First").createdAt(now).updatedAt(now).build());
        PicksTemplate second = templateRepository.saveAndFlush(PicksTemplate.builder().origin(PickOrigin.OFFICIAL).name("Second").createdAt(now).updatedAt(now).build());
        Pick firstPick = save(owner, first, PickVisibility.PUBLIC, now); Pick secondPick = save(owner, first, PickVisibility.PUBLIC, now); Pick thirdPick = save(owner, first, PickVisibility.PUBLIC, now);
        Pick secondTemplatePick = save(owner, second, PickVisibility.PUBLIC, now); save(otherOwner, first, PickVisibility.PUBLIC, now);

        Page<Pick> firstPage = repository.findByUserIdAndPicksTemplateIdOrderByCreatedAtDescIdDesc(owner.getId(), first.getId(), PageRequest.of(0, 2));
        Page<Pick> secondPage = repository.findByUserIdAndPicksTemplateIdOrderByCreatedAtDescIdDesc(owner.getId(), first.getId(), PageRequest.of(1, 2));
        assertThat(firstPage.getTotalElements()).isEqualTo(3); assertThat(firstPage.getTotalPages()).isEqualTo(2); assertThat(firstPage.getContent()).hasSize(2);
        assertThat(secondPage.getTotalElements()).isEqualTo(3); assertThat(secondPage.getTotalPages()).isEqualTo(2); assertThat(secondPage.getContent()).hasSize(1);
        assertThat(Stream.concat(firstPage.getContent().stream(), secondPage.getContent().stream()).map(Pick::getId).toList())
                .containsExactlyInAnyOrder(firstPick.getId(), secondPick.getId(), thirdPick.getId());
        Page<Pick> visiblePicks = repository.findVisibleByOwner(viewer.getId(), owner.getId(), null, PageRequest.of(0, 10));
        assertThat(visiblePicks.getTotalElements()).isEqualTo(4);
        assertThat(visiblePicks.getContent()).extracting(Pick::getId)
                .containsExactlyInAnyOrder(firstPick.getId(), secondPick.getId(), thirdPick.getId(), secondTemplatePick.getId());
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
