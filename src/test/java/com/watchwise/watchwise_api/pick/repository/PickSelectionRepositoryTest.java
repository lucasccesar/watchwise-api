package com.watchwise.watchwise_api.pick.repository;

import com.watchwise.watchwise_api.content.entity.*;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.pick.entity.*;
import com.watchwise.watchwise_api.pickstemplate.entity.*;
import com.watchwise.watchwise_api.pickstemplate.repository.*;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest @AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) @Testcontainers
class PickSelectionRepositoryTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) { r.add("spring.datasource.url", postgres::getJdbcUrl); r.add("spring.datasource.username", postgres::getUsername); r.add("spring.datasource.password", postgres::getPassword); }
    @Autowired PickSelectionRepository repository; @Autowired PickRepository pickRepository; @Autowired PicksTemplateRepository templateRepository; @Autowired PicksTemplateCategoryRepository categoryRepository; @Autowired UserRepository userRepository; @Autowired ContentRepository contentRepository;
    @Test void rejectsSecondSelectionForSamePickAndCategory() {
        LocalDateTime now = LocalDateTime.now(); User user = userRepository.saveAndFlush(User.builder().username("ana").email("ana@example.com").password("hash").profilePicture("https://example.com/a.png").createdAt(now).updatedAt(now).build());
        PicksTemplate template = templateRepository.saveAndFlush(PicksTemplate.builder().origin(PickOrigin.OFFICIAL).name("Awards").createdAt(now).updatedAt(now).build());
        PicksTemplateCategory category = categoryRepository.saveAndFlush(PicksTemplateCategory.builder().picksTemplate(template).name("Winner").group(PickCategoryGroup.PRIMARY).displayOrder(1).allowedType(PickAllowedType.MOVIE).optionMode(PickCategoryOptionMode.OPEN).createdAt(now).updatedAt(now).build());
        Pick pick = pickRepository.saveAndFlush(Pick.builder().user(user).picksTemplate(template).visibility(PickVisibility.PUBLIC).createdAt(now).updatedAt(now).build());
        Content first = contentRepository.saveAndFlush(Content.builder().tmdbId("550").type(ContentType.MOVIE).createdAt(now).updatedAt(now).build()); Content second = contentRepository.saveAndFlush(Content.builder().tmdbId("680").type(ContentType.MOVIE).createdAt(now).updatedAt(now).build());
        repository.saveAndFlush(PickSelection.builder().pick(pick).category(category).content(first).createdAt(now).updatedAt(now).build());
        assertThatThrownBy(() -> repository.saveAndFlush(PickSelection.builder().pick(pick).category(category).content(second).createdAt(now).updatedAt(now).build())).isInstanceOf(DataIntegrityViolationException.class);
    }
}
