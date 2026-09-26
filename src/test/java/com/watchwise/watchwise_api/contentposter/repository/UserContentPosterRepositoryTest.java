package com.watchwise.watchwise_api.contentposter.repository;

import com.watchwise.watchwise_api.common.validation.TmdbPosterUrlPolicy;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.contentposter.entity.UserContentPoster;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
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
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class UserContentPosterRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private UserContentPosterRepository userContentPosterRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ContentRepository contentRepository;

    @Autowired
    private EntityManager entityManager;

    private User lucas;
    private User marina;
    private Content fightClub;
    private Content pulpFiction;
    private Content breakingBad;

    @BeforeEach
    void setUp() {
        userContentPosterRepository.deleteAll();
        contentRepository.deleteAll();
        userRepository.deleteAll();

        lucas = userRepository.save(buildUser("lucas", "lucas@email.com"));
        marina = userRepository.save(buildUser("marina", "marina@email.com"));
        fightClub = contentRepository.save(buildContent("550", ContentType.MOVIE));
        pulpFiction = contentRepository.save(buildContent("680", ContentType.MOVIE));
        breakingBad = contentRepository.save(buildContent("1396", ContentType.SERIES));
    }

    @Test
    @DisplayName("[save] Should Persist Poster - When URL Uses The TMDB W342 Prefix")
    void shouldPersistPosterWhenUrlUsesTheTmdbW342Prefix() {
        UserContentPoster poster = userContentPosterRepository.saveAndFlush(
                buildPoster(lucas, fightClub, posterUrl("fight-club.png")));
        entityManager.clear();

        UserContentPoster reloaded = userContentPosterRepository
                .findByUserIdAndContentId(lucas.getId(), fightClub.getId())
                .orElseThrow();

        assertThat(reloaded.getId()).isEqualTo(poster.getId());
        assertThat(reloaded.getCustomPosterUrl()).isEqualTo(posterUrl("fight-club.png"));
    }

    @Test
    @DisplayName("[save] Should Reject Poster - When URL Uses An Unsupported Prefix")
    void shouldRejectPosterWhenUrlUsesAnUnsupportedPrefix() {
        assertThatThrownBy(() -> userContentPosterRepository.saveAndFlush(
                buildPoster(lucas, fightClub, "https://image.tmdb.org/t/p/w500/fight-club.png")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_user_content_posters_custom_poster_url");
    }

    @Test
    @DisplayName("[save] Should Reject Poster - When URL Has No Suffix")
    void shouldRejectPosterWhenUrlHasNoSuffix() {
        assertThatThrownBy(() -> userContentPosterRepository.saveAndFlush(
                buildPoster(lucas, fightClub, TmdbPosterUrlPolicy.PREFIX)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_user_content_posters_custom_poster_url");
    }

    @Test
    @DisplayName("[save] Should Reject Duplicate Poster - When User And Content Pair Already Exists")
    void shouldRejectDuplicatePosterWhenUserAndContentPairAlreadyExists() {
        userContentPosterRepository.saveAndFlush(
                buildPoster(lucas, fightClub, posterUrl("first.png")));

        assertThatThrownBy(() -> userContentPosterRepository.saveAndFlush(
                buildPoster(lucas, fightClub, posterUrl("second.png"))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_user_content_posters_user_id_content_id");
    }

    @Test
    @DisplayName("[upsert] Should Replace Existing Poster - When User And Content Pair Already Exists")
    void shouldReplaceExistingPosterWhenUserAndContentPairAlreadyExists() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();

        userContentPosterRepository.upsert(firstId, lucas.getId(), fightClub.getId(), posterUrl("first.png"));
        userContentPosterRepository.upsert(secondId, lucas.getId(), fightClub.getId(), posterUrl("second.png"));
        entityManager.clear();

        List<UserContentPoster> posters = userContentPosterRepository.findAll();

        assertThat(posters).hasSize(1);
        assertThat(posters.get(0).getId()).isEqualTo(firstId);
        assertThat(posters.get(0).getCustomPosterUrl()).isEqualTo(posterUrl("second.png"));
    }

    @Test
    @DisplayName("[findByUserIdAndContentIdIn] Should Return Every Requested Poster - When Multiple Rows Exist")
    void shouldReturnEveryRequestedPosterWhenMultipleRowsExist() {
        userContentPosterRepository.save(buildPoster(lucas, fightClub, posterUrl("fight-club.png")));
        userContentPosterRepository.save(buildPoster(lucas, pulpFiction, posterUrl("pulp-fiction.png")));
        userContentPosterRepository.saveAndFlush(buildPoster(marina, breakingBad, posterUrl("breaking-bad.png")));

        List<UserContentPosterRepository.ContentPosterProjection> result = userContentPosterRepository
                .findByUserIdAndContentIdIn(lucas.getId(), List.of(fightClub.getId(), pulpFiction.getId()));

        assertThat(result)
                .extracting(UserContentPosterRepository.ContentPosterProjection::getContentId,
                        UserContentPosterRepository.ContentPosterProjection::getCustomPosterUrl)
                .containsExactlyInAnyOrder(
                        tuple(fightClub.getId(), posterUrl("fight-club.png")),
                        tuple(pulpFiction.getId(), posterUrl("pulp-fiction.png")));
    }

    @Test
    @DisplayName("[deleteByUserIdAndContentId] Should Delete Only The Matching Poster")
    void shouldDeleteOnlyTheMatchingPoster() {
        userContentPosterRepository.save(buildPoster(lucas, fightClub, posterUrl("fight-club.png")));
        userContentPosterRepository.saveAndFlush(buildPoster(lucas, pulpFiction, posterUrl("pulp-fiction.png")));

        userContentPosterRepository.deleteByUserIdAndContentId(lucas.getId(), fightClub.getId());

        assertThat(userContentPosterRepository.findByUserIdAndContentId(lucas.getId(), fightClub.getId())).isEmpty();
        assertThat(userContentPosterRepository.findByUserIdAndContentId(lucas.getId(), pulpFiction.getId())).isPresent();
    }

    @Test
    @DisplayName("[findByUserIdAndSeriesTmdbIdIn] Should Return Posters Keyed By Series TMDB ID")
    void shouldReturnPostersKeyedBySeriesTmdbId() {
        userContentPosterRepository.saveAndFlush(
                buildPoster(lucas, breakingBad, posterUrl("breaking-bad.png")));

        List<UserContentPosterRepository.SeriesPosterProjection> result = userContentPosterRepository
                .findByUserIdAndSeriesTmdbIdIn(lucas.getId(), List.of("1396", "9999"));

        Map<String, String> postersBySeries = result.stream()
                .collect(Collectors.toMap(
                        UserContentPosterRepository.SeriesPosterProjection::getSeriesTmdbId,
                        UserContentPosterRepository.SeriesPosterProjection::getCustomPosterUrl));

        assertThat(postersBySeries).containsEntry("1396", posterUrl("breaking-bad.png"));
        assertThat(postersBySeries).doesNotContainKey("9999");
    }

    private UserContentPoster buildPoster(User user, Content content, String customPosterUrl) {
        LocalDateTime now = LocalDateTime.now();
        return UserContentPoster.builder()
                .user(user)
                .content(content)
                .customPosterUrl(customPosterUrl)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private User buildUser(String username, String email) {
        LocalDateTime now = LocalDateTime.now();
        return User.builder()
                .username(username)
                .email(email)
                .password("password")
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

    private String posterUrl(String suffix) {
        return TmdbPosterUrlPolicy.PREFIX + suffix;
    }
}
