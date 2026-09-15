package com.watchwise.watchwise_api.pickstemplate.repository;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.pickstemplate.entity.*;
import org.junit.jupiter.api.Test;
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
