package com.watchwise.watchwise_api.pick.repository;

import com.watchwise.watchwise_api.content.entity.*;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.pick.entity.*;
import com.watchwise.watchwise_api.pickstemplate.entity.*;
import com.watchwise.watchwise_api.pickstemplate.repository.*;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest @AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) @Testcontainers
class PickSelectionRepositoryTest {
    @Autowired EntityManager entityManager;
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) { r.add("spring.datasource.url", postgres::getJdbcUrl); r.add("spring.datasource.username", postgres::getUsername); r.add("spring.datasource.password", postgres::getPassword); }
    @Autowired PickSelectionRepository repository; @Autowired PickRepository pickRepository; @Autowired PicksTemplateRepository templateRepository; @Autowired PicksTemplateCategoryRepository categoryRepository; @Autowired UserRepository userRepository; @Autowired ContentRepository contentRepository;
    @Test void rejectsSecondSelectionForSamePickAndCategory() {
        LocalDateTime now = LocalDateTime.now(); User user = userRepository.saveAndFlush(User.builder().username("ana").name("ana").email("ana@example.com").password("hash").profilePicture("https://example.com/a.png").createdAt(now).updatedAt(now).build());
        PicksTemplate template = templateRepository.saveAndFlush(PicksTemplate.builder().origin(PickOrigin.OFFICIAL).name("Awards").createdAt(now).updatedAt(now).build());
        PicksTemplateCategory category = categoryRepository.saveAndFlush(PicksTemplateCategory.builder().picksTemplate(template).name("Winner").group(PickCategoryGroup.PRIMARY).displayOrder(1).allowedType(PickAllowedType.MOVIE).optionMode(PickCategoryOptionMode.OPEN).createdAt(now).updatedAt(now).build());
        Pick pick = pickRepository.saveAndFlush(Pick.builder().user(user).picksTemplate(template).visibility(PickVisibility.PUBLIC).createdAt(now).updatedAt(now).build());
        Content first = contentRepository.saveAndFlush(Content.builder().tmdbId("550").type(ContentType.MOVIE).createdAt(now).updatedAt(now).build()); Content second = contentRepository.saveAndFlush(Content.builder().tmdbId("680").type(ContentType.MOVIE).createdAt(now).updatedAt(now).build());
        repository.saveAndFlush(PickSelection.builder().pick(pick).category(category).content(first).createdAt(now).updatedAt(now).build());
        assertThatThrownBy(() -> repository.saveAndFlush(PickSelection.builder().pick(pick).category(category).content(second).createdAt(now).updatedAt(now).build())).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test
    @Transactional
    void batchLoadsCountsChecksCategoryAndTargetUsageAndLocksPickCategoryRow() {
        LocalDateTime now = LocalDateTime.now();
        User user = userRepository.saveAndFlush(User.builder().username("batch").name("batch").email("batch@example.com").password("hash").profilePicture("https://example.com/a.png").createdAt(now).updatedAt(now).build());
        PicksTemplate template = templateRepository.saveAndFlush(PicksTemplate.builder().origin(PickOrigin.OFFICIAL).name("Batch").createdAt(now).updatedAt(now).build());
        PicksTemplateCategory category = category(template, "Actor", 1, now);
        PicksTemplateCategory otherCategory = category(template, "Director", 2, now);
        Pick pick = pickRepository.saveAndFlush(Pick.builder().user(user).picksTemplate(template).visibility(PickVisibility.PUBLIC).createdAt(now).updatedAt(now).build());
        Pick requestedPick = pickRepository.saveAndFlush(Pick.builder().user(user).picksTemplate(template).visibility(PickVisibility.PUBLIC).createdAt(now).updatedAt(now).build());
        Pick excludedPick = pickRepository.saveAndFlush(Pick.builder().user(user).picksTemplate(template).visibility(PickVisibility.PUBLIC).createdAt(now).updatedAt(now).build());
        Content context = contentRepository.saveAndFlush(Content.builder().tmdbId("1399").type(ContentType.SERIES).createdAt(now).updatedAt(now).build());
        PickSelection selection = repository.saveAndFlush(PickSelection.builder().pick(pick).category(category).personTmdbId("287").contextContent(context).createdAt(now).updatedAt(now).build());
        PickSelection otherSelection = repository.saveAndFlush(PickSelection.builder().pick(pick).category(otherCategory).personTmdbId("6384").createdAt(now).updatedAt(now).build());
        PickSelection requestedSelection = repository.saveAndFlush(PickSelection.builder().pick(requestedPick).category(category).personTmdbId("100").createdAt(now).updatedAt(now).build());
        repository.saveAndFlush(PickSelection.builder().pick(excludedPick).category(category).personTmdbId("200").createdAt(now).updatedAt(now).build());

        assertThat(repository.findByPickIdIn(java.util.List.of(pick.getId(), requestedPick.getId())))
                .extracting(PickSelection::getId).containsExactlyInAnyOrder(selection.getId(), requestedSelection.getId(), otherSelection.getId());
        assertThat(repository.countByPickId(pick.getId())).isEqualTo(2);
        assertThat(repository.existsByCategoryId(category.getId())).isTrue();
        assertThat(repository.existsByCategoryIdAndContentIdAndPersonTmdbIdAndContextContentId(category.getId(), null, "287", context.getId())).isTrue();
        assertThat(repository.existsByCategoryIdAndContentIdAndPersonTmdbIdAndContextContentId(category.getId(), null, "287", null)).isFalse();
        assertThat(repository.findByPickIdAndCategoryIdForUpdate(pick.getId(), category.getId())).map(PickSelection::getId).contains(selection.getId());
    }

    @Test
    @DisplayName("[findByPickIdIn] Should Fetch Distinct Content And Context Targets In One Query - When The Persistence Context Is Cleared")
    void shouldFetchDistinctContentAndContextTargetsInOneQueryWhenPersistenceContextIsCleared() {
        LocalDateTime now = LocalDateTime.now();
        User user = userRepository.save(User.builder().username("targets").name("Targets")
                .email("targets@example.com").password("hash").createdAt(now).updatedAt(now).build());
        PicksTemplate template = templateRepository.save(PicksTemplate.builder().origin(PickOrigin.OFFICIAL)
                .name("Targets").createdAt(now).updatedAt(now).build());
        Pick pick = pickRepository.save(Pick.builder().user(user).picksTemplate(template)
                .visibility(PickVisibility.PRIVATE).createdAt(now).updatedAt(now).build());
        for (int index = 1; index <= 3; index++) {
            Content content = contentRepository.save(Content.builder().tmdbId("10" + index).type(ContentType.MOVIE)
                    .createdAt(now).updatedAt(now).build());
            Content context = contentRepository.save(Content.builder().tmdbId("20" + index).type(ContentType.SERIES)
                    .createdAt(now).updatedAt(now).build());
            PicksTemplateCategory movieCategory = category(template, "Movie " + index, index * 2, now);
            movieCategory.setAllowedType(PickAllowedType.MOVIE);
            repository.save(PickSelection.builder().pick(pick).category(movieCategory).content(content)
                    .createdAt(now).updatedAt(now).build());
            repository.save(PickSelection.builder().pick(pick).category(category(template, "Person " + index, index * 2 + 1, now))
                    .personTmdbId("30" + index).contextContent(context).createdAt(now).updatedAt(now).build());
        }
        repository.save(PickSelection.builder().pick(pick).category(category(template, "Isolated Person", 10, now))
                .personTmdbId("400").createdAt(now).updatedAt(now).build());
        entityManager.flush();
        entityManager.clear();
        var statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        var results = repository.findByPickIdIn(java.util.List.of(pick.getId()));

        assertThat(results).hasSize(7).allSatisfy(selection -> {
            assertThat(Hibernate.isInitialized(selection.getContent())).isTrue();
            assertThat(Hibernate.isInitialized(selection.getContextContent())).isTrue();
        });
        assertThat(results.stream().filter(selection -> selection.getContent() != null)
                .map(selection -> selection.getContent().getTmdbId())).containsExactlyInAnyOrder("101", "102", "103");
        assertThat(results.stream().filter(selection -> selection.getContextContent() != null)
                .map(selection -> selection.getContextContent().getTmdbId())).containsExactlyInAnyOrder("201", "202", "203");
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    private PicksTemplateCategory category(PicksTemplate template, String name, int order, LocalDateTime now) {
        return categoryRepository.saveAndFlush(PicksTemplateCategory.builder().picksTemplate(template).name(name).group(PickCategoryGroup.PRIMARY).displayOrder(order).allowedType(PickAllowedType.PERSON).optionMode(PickCategoryOptionMode.FIXED).createdAt(now).updatedAt(now).build());
    }
}
