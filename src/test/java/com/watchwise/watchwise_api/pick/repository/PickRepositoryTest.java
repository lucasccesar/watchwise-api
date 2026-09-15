package com.watchwise.watchwise_api.pick.repository;

import com.watchwise.watchwise_api.pick.entity.*;
import com.watchwise.watchwise_api.pickstemplate.entity.*;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.entity.Follower;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest @AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) @Testcontainers
class PickRepositoryTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) { r.add("spring.datasource.url", postgres::getJdbcUrl); r.add("spring.datasource.username", postgres::getUsername); r.add("spring.datasource.password", postgres::getPassword); }
    @Autowired PickRepository repository; @Autowired PicksTemplateRepository templateRepository; @Autowired UserRepository userRepository; @Autowired FollowerRepository followerRepository;
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
    private User user(String value, LocalDateTime now) { return userRepository.save(User.builder().username(value).email(value + "@example.com").password("hash").profilePicture("https://example.com/a.png").createdAt(now).updatedAt(now).build()); }
    private Pick save(User user, PicksTemplate template, PickVisibility visibility, LocalDateTime now) { return repository.save(Pick.builder().user(user).picksTemplate(template).visibility(visibility).createdAt(now).updatedAt(now).build()); }
}
