package com.watchwise.watchwise_api.pickstemplate.repository;

import com.watchwise.watchwise_api.comment.entity.Comment;
import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.entity.Follower;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.like.entity.Like;
import com.watchwise.watchwise_api.like.repository.LikeRepository;
import com.watchwise.watchwise_api.pick.entity.Pick;
import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pick.repository.PickRepository;
import com.watchwise.watchwise_api.pickstemplate.entity.PickOrigin;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class PicksTemplateRepositoryTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl); registry.add("spring.datasource.username", postgres::getUsername); registry.add("spring.datasource.password", postgres::getPassword);
    }
    @Autowired PicksTemplateRepository repository;
    @Autowired UserRepository userRepository;
    @Autowired PickRepository pickRepository;
    @Autowired CommentRepository commentRepository;
    @Autowired LikeRepository likeRepository;
    @Autowired FollowerRepository followerRepository;
    @PersistenceContext EntityManager entityManager;

    @Test
    void savesTemplateWithNullableCreator() {
        LocalDateTime now = LocalDateTime.now();
        PicksTemplate template = repository.saveAndFlush(PicksTemplate.builder().origin(PickOrigin.OFFICIAL).name("Awards").createdAt(now).updatedAt(now).build());
        assertThat(template.getId()).isNotNull();
        assertThat(template.getCreator()).isNull();
    }

    @Test
    void searchesByOptionalOriginAndEscapedNameWithPagination() {
        LocalDateTime now = LocalDateTime.now();
        repository.save(PicksTemplate.builder().origin(PickOrigin.OFFICIAL).name("100% Awards").createdAt(now).updatedAt(now).build());
        repository.save(PicksTemplate.builder().origin(PickOrigin.COMMUNITY).name("1000 Awards").createdAt(now).updatedAt(now).build());
        repository.saveAndFlush(PicksTemplate.builder().origin(PickOrigin.OFFICIAL).name("Other Awards").createdAt(now).updatedAt(now).build());

        Page<PicksTemplate> result = repository.search(PickOrigin.OFFICIAL, "100\\%", PageRequest.of(0, 1));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getTotalPages()).isEqualTo(1);
        assertThat(result.getContent()).extracting(PicksTemplate::getName).containsExactly("100% Awards");
    }

    @Test
    void searchesRecentTemplatesInCreationOrderWithOriginAndEscapedNameFilters() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 18, 12, 0);
        saveTemplate("Rank% older", PickOrigin.OFFICIAL, now.minusDays(1));
        saveTemplate("Rank% newer", PickOrigin.OFFICIAL, now);
        saveTemplate("Rank% community", PickOrigin.COMMUNITY, now.plusDays(1));

        Page<PicksTemplate> result = repository.searchRecent(
                PickOrigin.OFFICIAL, "rank\\%", PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(PicksTemplate::getName)
                .containsExactly("Rank% newer", "Rank% older");
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    void findsFeedCandidatesOnlyForFollowedCreatorsAndHonorsCursor() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 23, 12, 0);
        User followedCreator = saveUser("feed-template-followed", now);
        User otherCreator = saveUser("feed-template-other", now);

        PicksTemplate newest = repository.saveAndFlush(PicksTemplate.builder()
                .creator(followedCreator).origin(PickOrigin.COMMUNITY).name("Newest")
                .createdAt(now.plusMinutes(3)).updatedAt(now.plusMinutes(3)).build());
        PicksTemplate older = repository.saveAndFlush(PicksTemplate.builder()
                .creator(followedCreator).origin(PickOrigin.COMMUNITY).name("Older")
                .createdAt(now.plusMinutes(1)).updatedAt(now.plusMinutes(1)).build());
        repository.saveAndFlush(PicksTemplate.builder()
                .creator(otherCreator).origin(PickOrigin.COMMUNITY).name("Other")
                .createdAt(now.plusMinutes(2)).updatedAt(now.plusMinutes(2)).build());
        repository.saveAndFlush(PicksTemplate.builder()
                .origin(PickOrigin.OFFICIAL).name("Detached")
                .createdAt(now.plusMinutes(4)).updatedAt(now.plusMinutes(4)).build());

        List<PicksTemplate> candidates = repository.findFeedCandidates(
                java.util.List.of(followedCreator.getId()), now.plusMinutes(3), null, PageRequest.of(0, 10));

        assertThat(candidates).extracting(PicksTemplate::getId)
                .containsExactly(older.getId());
        assertThat(candidates).doesNotContain(newest);
    }

    @Test
    void searchesMostPickedUsingOnlyPicksVisibleToViewer() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 18, 12, 0);
        User viewer = saveUser("ranking-viewer", now);
        User owner = saveUser("ranking-owner", now);
        User acceptedFollowedOwner = saveUser("ranking-followed", now);
        User stranger = saveUser("ranking-stranger", now);
        followerRepository.saveAndFlush(Follower.builder()
                .follower(viewer).followed(acceptedFollowedOwner).status(FollowStatus.ACCEPTED).createdAt(now).build());

        PicksTemplate mostPicked = saveTemplate("Most picked", PickOrigin.OFFICIAL, now);
        PicksTemplate lessPicked = saveTemplate("Less picked", PickOrigin.OFFICIAL, now.minusDays(1));
        PicksTemplate privateHeavy = saveTemplate("Private heavy", PickOrigin.OFFICIAL, now.plusDays(1));
        PicksTemplate excludedOrigin = saveTemplate("Community picks", PickOrigin.COMMUNITY, now.plusDays(1));

        savePick(viewer, mostPicked, PickVisibility.PRIVATE, now);
        savePick(owner, mostPicked, PickVisibility.PUBLIC, now);
        savePick(acceptedFollowedOwner, mostPicked, PickVisibility.FOLLOWERS, now);
        savePick(stranger, mostPicked, PickVisibility.PRIVATE, now);
        savePick(owner, lessPicked, PickVisibility.PUBLIC, now);
        savePick(acceptedFollowedOwner, lessPicked, PickVisibility.FOLLOWERS, now);
        savePick(stranger, privateHeavy, PickVisibility.PRIVATE, now);
        savePick(stranger, privateHeavy, PickVisibility.PRIVATE, now.plusSeconds(1));
        savePick(stranger, privateHeavy, PickVisibility.PRIVATE, now.plusSeconds(2));
        savePick(stranger, privateHeavy, PickVisibility.PRIVATE, now.plusSeconds(3));
        savePick(stranger, excludedOrigin, PickVisibility.PRIVATE, now);

        Page<PicksTemplate> result = repository.searchMostPicked(
                viewer.getId(), PickOrigin.OFFICIAL, null, PageRequest.of(0, 2));
        Page<PicksTemplate> nextPage = repository.searchMostPicked(
                viewer.getId(), PickOrigin.OFFICIAL, null, PageRequest.of(1, 2));

        assertThat(result.getContent()).extracting(PicksTemplate::getName)
                .containsExactly("Most picked", "Less picked");
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getTotalPages()).isEqualTo(2);
        assertThat(nextPage.getContent()).extracting(PicksTemplate::getName)
                .containsExactly("Private heavy");
        assertThat(nextPage.getTotalElements()).isEqualTo(3);
        assertThat(nextPage.getTotalPages()).isEqualTo(2);
    }

    @Test
    void searchesPopularWeekAtInclusiveBoundaryWithIndependentActivityCountsAndVisibility() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        LocalDateTime since = now.minusDays(7);
        User viewer = saveUser("weekly-viewer", now);
        User owner = saveUser("weekly-owner", now);
        User acceptedFollowedOwner = saveUser("weekly-followed", now);
        User stranger = saveUser("weekly-stranger", now);
        User extraLike = saveUser("weekly-extra-like", now);
        followerRepository.saveAndFlush(Follower.builder()
                .follower(viewer).followed(acceptedFollowedOwner).status(FollowStatus.ACCEPTED).createdAt(now).build());

        PicksTemplate highActivity = saveTemplate("Weekly high activity", PickOrigin.OFFICIAL, now.minusDays(6));
        PicksTemplate winner = saveTemplate("Weekly winner", PickOrigin.OFFICIAL, now.minusDays(2));
        PicksTemplate runnerUp = saveTemplate("Weekly runner up", PickOrigin.OFFICIAL, now.minusDays(1));
        PicksTemplate tieLowId = saveTemplate(
                UUID.fromString("00000000-0000-0000-0000-000000000001"), "Weekly tie low", PickOrigin.OFFICIAL, now.minusDays(3));
        PicksTemplate tieHighId = saveTemplate(
                UUID.fromString("00000000-0000-0000-0000-000000000002"), "Weekly tie high", PickOrigin.OFFICIAL, now.minusDays(3));
        PicksTemplate third = saveTemplate("Weekly third", PickOrigin.OFFICIAL, now.minusDays(4));
        PicksTemplate oldOnly = saveTemplate("Weekly old only", PickOrigin.OFFICIAL, now.minusDays(5));
        saveTemplate("Weekly score zero", PickOrigin.OFFICIAL, now.minusDays(5));
        saveTemplate("Weekly community", PickOrigin.COMMUNITY, now);

        saveTemplateLike(owner, highActivity, since.plusSeconds(1));
        saveTemplateLike(viewer, highActivity, since.plusSeconds(2));
        saveTemplateLike(acceptedFollowedOwner, highActivity, since.plusSeconds(3));
        saveTemplateLike(stranger, highActivity, since.plusSeconds(4));
        saveTemplateLike(extraLike, highActivity, since.plusSeconds(5));
        saveTemplateComment(owner, highActivity, since.plusSeconds(6));
        savePick(owner, highActivity, PickVisibility.PUBLIC, since.plusSeconds(7));

        saveTemplateLike(owner, winner, since);
        saveTemplateLike(viewer, winner, since.plusSeconds(1));
        saveTemplateComment(owner, winner, since.plusSeconds(2));
        saveTemplateComment(stranger, winner, since.plusSeconds(3));
        savePick(owner, winner, PickVisibility.PUBLIC, since.plusSeconds(4));
        savePick(acceptedFollowedOwner, winner, PickVisibility.FOLLOWERS, since.plusSeconds(5));

        saveTemplateLike(owner, runnerUp, since.plusSeconds(8));
        saveTemplateComment(owner, runnerUp, since.plusSeconds(9));
        savePick(stranger, runnerUp, PickVisibility.PRIVATE, since.plusSeconds(10));

        savePick(acceptedFollowedOwner, tieLowId, PickVisibility.FOLLOWERS, since.plusSeconds(11));
        savePick(owner, tieHighId, PickVisibility.PUBLIC, since.plusSeconds(12));
        savePick(owner, third, PickVisibility.PUBLIC, since.plusSeconds(13));

        saveTemplateLike(owner, oldOnly, since.minusNanos(1_000));
        saveTemplateComment(owner, oldOnly, since.minusNanos(1_000));
        savePick(owner, oldOnly, PickVisibility.PUBLIC, since.minusNanos(1_000));

        Page<PicksTemplate> firstPage = repository.searchPopularWeek(
                viewer.getId(), since, PickOrigin.OFFICIAL, null, PageRequest.of(0, 2));
        Page<PicksTemplate> secondPage = repository.searchPopularWeek(
                viewer.getId(), since, PickOrigin.OFFICIAL, null, PageRequest.of(1, 2));
        Page<PicksTemplate> thirdPage = repository.searchPopularWeek(
                viewer.getId(), since, PickOrigin.OFFICIAL, null, PageRequest.of(2, 2));

        assertThat(firstPage.getContent()).extracting(PicksTemplate::getName)
                .containsExactly("Weekly high activity", "Weekly winner");
        assertThat(secondPage.getContent()).extracting(PicksTemplate::getName)
                .containsExactly("Weekly runner up", "Weekly tie high");
        assertThat(thirdPage.getContent()).extracting(PicksTemplate::getName)
                .containsExactly("Weekly tie low", "Weekly third");
        assertThat(firstPage.getTotalElements()).isEqualTo(6);
        assertThat(firstPage.getTotalPages()).isEqualTo(3);
        assertThat(secondPage.getTotalElements()).isEqualTo(6);
        assertThat(secondPage.getTotalPages()).isEqualTo(3);
        assertThat(thirdPage.getTotalElements()).isEqualTo(6);
        assertThat(thirdPage.getTotalPages()).isEqualTo(3);
    }

    @Test
    @Transactional
    void findsTemplateByIdWhileHoldingPessimisticWriteLock() {
        LocalDateTime now = LocalDateTime.now();
        PicksTemplate template = repository.saveAndFlush(PicksTemplate.builder().origin(PickOrigin.OFFICIAL).name("Awards").createdAt(now).updatedAt(now).build());

        assertThat(repository.findByIdForUpdate(template.getId())).contains(template);
    }

    private User saveUser(String username, LocalDateTime now) {
        return userRepository.saveAndFlush(User.builder()
                .username(username)
                .email(username + "@example.com")
                .password("hash")
                .profilePicture("https://example.com/profile.png")
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private PicksTemplate saveTemplate(String name, PickOrigin origin, LocalDateTime createdAt) {
        return repository.saveAndFlush(PicksTemplate.builder()
                .origin(origin).name(name).createdAt(createdAt).updatedAt(createdAt).build());
    }

    private PicksTemplate saveTemplate(UUID id, String name, PickOrigin origin, LocalDateTime createdAt) {
        entityManager.createNativeQuery("""
                        insert into picks_templates (id, origin, name, created_at, updated_at)
                        values (:id, :origin, :name, :createdAt, :updatedAt)
                        """)
                .setParameter("id", id)
                .setParameter("origin", origin.name())
                .setParameter("name", name)
                .setParameter("createdAt", createdAt)
                .setParameter("updatedAt", createdAt)
                .executeUpdate();
        return repository.findById(id).orElseThrow();
    }

    private Pick savePick(User user, PicksTemplate template, PickVisibility visibility, LocalDateTime createdAt) {
        return pickRepository.saveAndFlush(Pick.builder()
                .user(user).picksTemplate(template).visibility(visibility)
                .createdAt(createdAt).updatedAt(createdAt).build());
    }

    private void saveTemplateLike(User user, PicksTemplate template, LocalDateTime createdAt) {
        likeRepository.saveAndFlush(Like.builder().user(user).picksTemplate(template).createdAt(createdAt).build());
    }

    private void saveTemplateComment(User user, PicksTemplate template, LocalDateTime createdAt) {
        commentRepository.saveAndFlush(Comment.builder()
                .user(user).picksTemplate(template).text("Weekly comment")
                .containsSpoiler(false).createdAt(createdAt).updatedAt(createdAt).build());
    }
}
