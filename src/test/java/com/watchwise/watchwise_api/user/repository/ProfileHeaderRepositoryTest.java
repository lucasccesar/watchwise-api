package com.watchwise.watchwise_api.user.repository;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.user.dto.ProfileHeaderDTO.Access;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.service.impl.ProfileHeaderReader;
import com.watchwise.watchwise_api.userblock.service.UserBlockAccessService;
import com.watchwise_test.WatchwiseJpaTestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ContextConfiguration(classes = WatchwiseJpaTestApplication.class)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ProfileHeaderReader.class)
@Testcontainers
class ProfileHeaderRepositoryTest {
    private static final String DATABASE = "ww_profile_7i4";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName(DATABASE);

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        String url = postgres.getJdbcUrl();
        int port = postgres.getMappedPort(5432);
        if (!List.of("localhost", "127.0.0.1").contains(postgres.getHost()) || port == 15432 || port == 15433
                || !url.split("[?]", 2)[0].equals("jdbc:postgresql://" + postgres.getHost() + ":" + port + "/" + DATABASE)) {
            throw new IllegalStateException("Unexpected profile test datasource");
        }
        try (var connection = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT current_database(), current_setting('server_version_num')")) {
            if (!rows.next() || !DATABASE.equals(rows.getString(1)) || rows.getInt(2) / 10000 != 16) {
                throw new IllegalStateException("Unexpected profile test database identity");
            }
            System.out.println("Verified isolated PostgreSQL datasource before Spring: " + url);
        } catch (SQLException error) {
            throw new IllegalStateException("Failed to verify profile test datasource", error);
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.docker.compose.enabled", () -> false);
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired UserRepository users;
    @Autowired ContentRepository contents;
    @Autowired DiaryEntryRepository diary;
    @Autowired ProfileHeaderReader reader;
    @MockitoBean UserBlockAccessService blocks;

    @Test
    void matchesExactUsernameAndProjectsOnlyTheLegacyInclusiveWindowForBothModes() {
        var now = LocalDateTime.now();
        var target = users.saveAndFlush(User.builder().username("éxact_%").name("Original")
                .email("profile-test@example.test").password("unused-test-value").banner("/banner").description("Bio")
                .profilePicture("/avatar").createdAt(now).updatedAt(now).isProfilePublic(false).build());
        var movie = contents.saveAndFlush(Content.builder().type(ContentType.MOVIE).tmdbId("987651")
                .runtimeMinutes(95).createdAt(now).updatedAt(now).build());
        var episode = contents.saveAndFlush(Content.builder().type(ContentType.EPISODE).seriesTmdbId("987652")
                .seasonNumber(1).episodeNumber(2).runtimeMinutes(43).createdAt(now).updatedAt(now).build());
        LocalDate end = now.toLocalDate();
        save(target, movie, end.minusDays(31), 1);
        save(target, movie, end.minusDays(30), 2);
        save(target, episode, end, 1);

        assertThat(users.findProfileHeaderIdentityByUsername("ÉXACT_%")).get()
                .satisfies(identity -> assertThat(identity.getId()).isEqualTo(target.getId()));
        assertThat(users.findProfileHeaderIdentityByUsername("éxact")).isEmpty();
        assertThat(users.findProfileHeaderIdentityByUsername("éxact__")).isEmpty();
        var header = reader.read(target.getId(), "@ÉXACT_%");
        assertThat(header.access()).isEqualTo(Access.VISIBLE);
        assertThat(header.username()).isEqualTo("éxact_%");
        assertThat(header.description()).isEqualTo("Bio");
        assertThat(header.profilePicture()).isEqualTo("/avatar");
        assertThat(header.banner()).isEqualTo("/banner");
        assertThat(header.minutesWatchedMoviesLast30Days()).isEqualTo(95L);
        assertThat(header.minutesWatchedEpisodesLast30Days()).isEqualTo(43L);
    }

    private void save(User user, Content content, LocalDate date, int watchNumber) {
        var time = date.atTime(10, 0);
        diary.saveAndFlush(DiaryEntry.builder().user(user).content(content).watchedDate(date).watchNumber(watchNumber)
                .createdAt(time).updatedAt(time).build());
    }
}

