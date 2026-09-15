package com.watchwise.watchwise_api.pickstemplate.repository;

import com.watchwise.watchwise_api.pickstemplate.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest @AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) @Testcontainers
class PicksTemplateCategoryRepositoryTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) { r.add("spring.datasource.url", postgres::getJdbcUrl); r.add("spring.datasource.username", postgres::getUsername); r.add("spring.datasource.password", postgres::getPassword); }
    @Autowired PicksTemplateRepository templateRepository;
    @Autowired PicksTemplateCategoryRepository repository;
    @Test void savesCategoryWithMappedCategoryGroupColumn() {
        LocalDateTime now = LocalDateTime.now();
        PicksTemplate template = templateRepository.saveAndFlush(PicksTemplate.builder().origin(PickOrigin.COMMUNITY).name("Top").createdAt(now).updatedAt(now).build());
        PicksTemplateCategory category = repository.saveAndFlush(PicksTemplateCategory.builder().picksTemplate(template).name("Best film").group(PickCategoryGroup.PRIMARY).displayOrder(1).allowedType(PickAllowedType.MOVIE).optionMode(PickCategoryOptionMode.OPEN).createdAt(now).updatedAt(now).build());
        assertThat(category.getId()).isNotNull();
    }

    @Test
    void ordersCategoriesAndScopesLookupAndUsageToTheRequestedTemplate() {
        LocalDateTime now = LocalDateTime.now();
        PicksTemplate template = templateRepository.save(PicksTemplate.builder().origin(PickOrigin.OFFICIAL).name("Awards").createdAt(now).updatedAt(now).build());
        PicksTemplate otherTemplate = templateRepository.saveAndFlush(PicksTemplate.builder().origin(PickOrigin.OFFICIAL).name("Other").createdAt(now).updatedAt(now).build());
        PicksTemplateCategory second = repository.save(category(template, "Second", PickCategoryGroup.PRIMARY, 2, now));
        PicksTemplateCategory first = repository.saveAndFlush(category(template, "First", PickCategoryGroup.PRIMARY, 1, now));
        PicksTemplateCategory other = repository.saveAndFlush(category(otherTemplate, "Other", PickCategoryGroup.PRIMARY, 1, now));

        List<PicksTemplateCategory> result = repository.findByPicksTemplateIdOrderByGroupAscDisplayOrderAsc(template.getId());

        assertThat(result).extracting(PicksTemplateCategory::getId).containsExactly(first.getId(), second.getId());
        assertThat(repository.findByIdAndPicksTemplateId(first.getId(), otherTemplate.getId())).isEmpty();
        assertThat(repository.existsByPicksTemplateId(template.getId())).isTrue();
        assertThat(repository.findByIdAndPicksTemplateId(other.getId(), template.getId())).isEmpty();
    }

    @Test
    @Transactional
    void locksCategoryByIdForUpdate() {
        LocalDateTime now = LocalDateTime.now();
        PicksTemplate template = templateRepository.saveAndFlush(PicksTemplate.builder().origin(PickOrigin.OFFICIAL).name("Awards").createdAt(now).updatedAt(now).build());
        PicksTemplateCategory category = repository.saveAndFlush(category(template, "Winner", PickCategoryGroup.PRIMARY, 1, now));

        assertThat(repository.findByIdForUpdate(category.getId())).map(PicksTemplateCategory::getId).contains(category.getId());
    }

    private PicksTemplateCategory category(PicksTemplate template, String name, PickCategoryGroup group, int order, LocalDateTime now) {
        return PicksTemplateCategory.builder().picksTemplate(template).name(name).group(group).displayOrder(order)
                .allowedType(PickAllowedType.MOVIE).optionMode(PickCategoryOptionMode.FIXED).createdAt(now).updatedAt(now).build();
    }
}
