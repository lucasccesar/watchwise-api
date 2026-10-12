package com.watchwise.watchwise_api.pickstemplate.repository;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.pickstemplate.entity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.springframework.data.domain.Sort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest @AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) @Testcontainers
class PicksTemplateOptionRepositoryTest {
    @Autowired EntityManager entityManager;
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) { r.add("spring.datasource.url", postgres::getJdbcUrl); r.add("spring.datasource.username", postgres::getUsername); r.add("spring.datasource.password", postgres::getPassword); }
    @Autowired PicksTemplateRepository templateRepository; @Autowired PicksTemplateCategoryRepository categoryRepository; @Autowired PicksTemplateOptionRepository repository; @Autowired ContentRepository contentRepository;
    @Test void rejectsDuplicateFixedContentTargetIncludingNullContext() {
        LocalDateTime now = LocalDateTime.now();
        PicksTemplate template = templateRepository.saveAndFlush(PicksTemplate.builder().origin(PickOrigin.OFFICIAL).name("Awards").createdAt(now).updatedAt(now).build());
        PicksTemplateCategory category = categoryRepository.saveAndFlush(PicksTemplateCategory.builder().picksTemplate(template).name("Winner").group(PickCategoryGroup.PRIMARY).displayOrder(1).allowedType(PickAllowedType.MOVIE).optionMode(PickCategoryOptionMode.FIXED).createdAt(now).updatedAt(now).build());
        Content content = contentRepository.saveAndFlush(Content.builder().tmdbId("550").type(ContentType.MOVIE).createdAt(now).updatedAt(now).build());
        repository.saveAndFlush(PicksTemplateOption.builder().category(category).content(content).createdAt(now).build());
        assertThatThrownBy(() -> repository.saveAndFlush(PicksTemplateOption.builder().category(category).content(content).createdAt(now).build())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void listsOptionsAndMatchesFullTargetTupleForIsolatedAndContextualPeople() {
        LocalDateTime now = LocalDateTime.now();
        PicksTemplate template = templateRepository.saveAndFlush(PicksTemplate.builder().origin(PickOrigin.OFFICIAL).name("Awards").createdAt(now).updatedAt(now).build());
        PicksTemplateCategory category = categoryRepository.saveAndFlush(PicksTemplateCategory.builder().picksTemplate(template).name("Actor").group(PickCategoryGroup.PRIMARY).displayOrder(1).allowedType(PickAllowedType.PERSON).optionMode(PickCategoryOptionMode.FIXED).createdAt(now).updatedAt(now).build());
        Content context = contentRepository.saveAndFlush(Content.builder().tmdbId("1399").type(ContentType.SERIES).createdAt(now).updatedAt(now).build());
        repository.save(PicksTemplateOption.builder().category(category).personTmdbId("287").createdAt(now).build());
        repository.saveAndFlush(PicksTemplateOption.builder().category(category).personTmdbId("287").contextContent(context).createdAt(now).build());

        assertThat(repository.findByCategoryId(category.getId())).hasSize(2);
        assertThat(repository.existsByCategoryIdAndContentIdAndPersonTmdbIdAndContextContentId(category.getId(), null, "287", null)).isTrue();
        assertThat(repository.existsByCategoryIdAndContentIdAndPersonTmdbIdAndContextContentId(category.getId(), null, "287", context.getId())).isTrue();
        assertThat(repository.existsByCategoryIdAndContentIdAndPersonTmdbIdAndContextContentId(category.getId(), null, "287", java.util.UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("[findByCategoryIdIn] Should Fetch Distinct Content And Context Targets In One Query - When The Persistence Context Is Cleared")
    void shouldFetchDistinctContentAndContextTargetsInOneQueryWhenPersistenceContextIsCleared() {
        var categoryIds = persistDistinctTargets();
        var statistics = clearedStatistics();

        var results = repository.findByCategoryIdIn(categoryIds);

        assertThat(results).hasSize(7);
        assertTargetsInitialized(results);
        assertThat(results.stream().filter(option -> option.getContent() != null)
                .map(option -> option.getContent().getTmdbId())).containsExactlyInAnyOrder("101", "102", "103");
        assertThat(results.stream().filter(option -> option.getContextContent() != null)
                .map(option -> option.getContextContent().getTmdbId())).containsExactlyInAnyOrder("201", "202", "203");
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("[findByCategoryId] Should Fetch Distinct Targets And Preserve Page Counts - When The Persistence Context Is Cleared")
    void shouldFetchDistinctTargetsAndPreservePageCountsWhenPersistenceContextIsCleared() {
        var categoryIds = persistDistinctTargets();
        for (int index = 0; index < categoryIds.size(); index++) {
            var statistics = clearedStatistics();

            var page = repository.findByCategoryId(categoryIds.get(index), PageRequest.of(0, 2, Sort.by("createdAt")));

            assertThat(page.getContent()).hasSize(2);
            assertTargetsInitialized(page.getContent());
            assertThat(page.getTotalElements()).isEqualTo(index == 0 ? 3 : 4);
            assertThat(page.getTotalPages()).isEqualTo(2);
            assertThat(page.hasNext()).isTrue();
            assertThat(page.getNumber()).isZero();
            assertThat(page.getSize()).isEqualTo(2);
            assertThat(page.getContent()).extracting(option -> indexTarget(option)).containsExactly(
                    index == 0 ? "101" : "201", index == 0 ? "102" : "202");
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("[findByCategoryId] Should Fetch Raw Option Targets In One Query - When The Persistence Context Is Cleared")
    void shouldFetchRawOptionTargetsInOneQueryWhenPersistenceContextIsCleared() {
        var categoryIds = persistDistinctTargets();
        for (var categoryId : categoryIds) {
            var statistics = clearedStatistics();

            var results = repository.findByCategoryId(categoryId);

            assertThat(results).hasSize(categoryId.equals(categoryIds.getFirst()) ? 3 : 4);
            assertTargetsInitialized(results);
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        }
    }

    private java.util.List<java.util.UUID> persistDistinctTargets() {
        LocalDateTime now = LocalDateTime.now();
        PicksTemplate template = templateRepository.save(PicksTemplate.builder().origin(PickOrigin.OFFICIAL)
                .name("Targets").createdAt(now).updatedAt(now).build());
        PicksTemplateCategory movies = categoryRepository.save(PicksTemplateCategory.builder().picksTemplate(template)
                .name("Movies").group(PickCategoryGroup.PRIMARY).displayOrder(1).allowedType(PickAllowedType.MOVIE)
                .optionMode(PickCategoryOptionMode.FIXED).createdAt(now).updatedAt(now).build());
        PicksTemplateCategory people = categoryRepository.save(PicksTemplateCategory.builder().picksTemplate(template)
                .name("People").group(PickCategoryGroup.PRIMARY).displayOrder(2).allowedType(PickAllowedType.PERSON)
                .optionMode(PickCategoryOptionMode.FIXED).createdAt(now).updatedAt(now).build());
        for (int index = 1; index <= 3; index++) {
            Content content = contentRepository.save(Content.builder().tmdbId("10" + index).type(ContentType.MOVIE)
                    .createdAt(now).updatedAt(now).build());
            Content context = contentRepository.save(Content.builder().tmdbId("20" + index).type(ContentType.SERIES)
                    .createdAt(now).updatedAt(now).build());
            repository.save(PicksTemplateOption.builder().category(movies).content(content).createdAt(now.plusSeconds(index)).build());
            repository.save(PicksTemplateOption.builder().category(people).personTmdbId("30" + index)
                    .contextContent(context).createdAt(now.plusSeconds(index)).build());
        }
        repository.save(PicksTemplateOption.builder().category(people).personTmdbId("400").createdAt(now.plusSeconds(4)).build());
        entityManager.flush();
        return java.util.List.of(movies.getId(), people.getId());
    }

    private org.hibernate.stat.Statistics clearedStatistics() {
        entityManager.flush();
        entityManager.clear();
        var statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        return statistics;
    }

    private void assertTargetsInitialized(java.util.List<PicksTemplateOption> options) {
        assertThat(options).allSatisfy(option -> {
            assertThat(Hibernate.isInitialized(option.getContent())).isTrue();
            assertThat(Hibernate.isInitialized(option.getContextContent())).isTrue();
            if (option.getContent() != null) assertThat(option.getContent().getType()).isEqualTo(ContentType.MOVIE);
            if (option.getContextContent() != null) assertThat(option.getContextContent().getType()).isEqualTo(ContentType.SERIES);
        });
    }

    private String indexTarget(PicksTemplateOption option) {
        return option.getContent() != null ? option.getContent().getTmdbId() : option.getContextContent().getTmdbId();
    }

    @Test
    void pagesOptionsInTheDatabase() {
        LocalDateTime now = LocalDateTime.now();
        PicksTemplate template = templateRepository.saveAndFlush(PicksTemplate.builder().origin(PickOrigin.OFFICIAL).name("Awards").createdAt(now).updatedAt(now).build());
        PicksTemplateCategory category = categoryRepository.saveAndFlush(PicksTemplateCategory.builder().picksTemplate(template).name("Actor").group(PickCategoryGroup.PRIMARY).displayOrder(1).allowedType(PickAllowedType.PERSON).optionMode(PickCategoryOptionMode.FIXED).createdAt(now).updatedAt(now).build());
        repository.saveAllAndFlush(java.util.List.of(
                PicksTemplateOption.builder().category(category).personTmdbId("287").createdAt(now).build(),
                PicksTemplateOption.builder().category(category).personTmdbId("288").createdAt(now).build()));

        Page<PicksTemplateOption> page = repository.findByCategoryId(category.getId(), PageRequest.of(1, 1));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.hasNext()).isFalse();
    }
}
