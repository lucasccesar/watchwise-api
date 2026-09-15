package com.watchwise.watchwise_api.pickstemplate.repository;

import com.watchwise.watchwise_api.pickstemplate.entity.PickOrigin;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
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
    @Transactional
    void findsTemplateByIdWhileHoldingPessimisticWriteLock() {
        LocalDateTime now = LocalDateTime.now();
        PicksTemplate template = repository.saveAndFlush(PicksTemplate.builder().origin(PickOrigin.OFFICIAL).name("Awards").createdAt(now).updatedAt(now).build());

        assertThat(repository.findByIdForUpdate(template.getId())).contains(template);
    }
}
