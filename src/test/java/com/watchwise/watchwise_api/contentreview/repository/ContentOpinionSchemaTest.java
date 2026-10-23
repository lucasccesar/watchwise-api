package com.watchwise.watchwise_api.contentreview.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ContentOpinionSchemaTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;

    @Test void rejectsWrongTypeAndOutOfRangeScore() {
        assertTableExists("content_ratings");
        UUID owner = user();
        UUID series = content("SERIES");
        assertThatThrownBy(() -> rating(owner, series, "MOVIE", 8)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> rating(owner, series, "SEASON", 8)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> rating(owner, series, "SERIES", 0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> rating(owner, series, "SERIES", 11)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select count(*) from content_ratings where user_id = ?", Integer.class, owner)).isZero();
    }

    @Test void allowsTwoSeriesReviewsButOnlyOneWrittenSeasonReviewPerPass() {
        assertTableExists("content_reviews");
        UUID owner = user();
        UUID series = content("SERIES");
        UUID season = content("SEASON");
        review(owner, series, "SERIES", 1, "one");
        review(owner, series, "SERIES", 1, "two");
        review(owner, season, "SEASON", 1, "one");
        assertThatThrownBy(() -> review(owner, season, "SEASON", 1, "two")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select count(*) from content_reviews where user_id = ?", Integer.class, owner)).isEqualTo(3);
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users(id, username, email, password, profile_picture, name) values (?, ?, ?, 'hash', 'picture', 'Test')", id, id.toString(), id + "@example.com");
        return id;
    }

    private void assertTableExists(String table) {
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema = 'public' and table_name = ?", Integer.class, table)).isEqualTo(1);
    }

    private UUID content(String type) {
        UUID id = UUID.randomUUID();
        String tmdbId = Integer.toUnsignedString(id.hashCode());
        if (type.equals("SEASON")) jdbc.update("insert into contents(id,type,series_tmdb_id,season_number) values (?,'SEASON',?,1)", id, tmdbId);
        else jdbc.update("insert into contents(id,type,tmdb_id) values (?,'SERIES',?)", id, tmdbId);
        return id;
    }

    private void rating(UUID owner, UUID content, String type, int score) {
        jdbc.update("insert into content_ratings(id,user_id,content_id,content_type,score) values (?,?,?,?,?)", UUID.randomUUID(), owner, content, type, score);
    }

    private void review(UUID owner, UUID content, String type, int pass, String comment) {
        jdbc.update("insert into content_reviews(id,user_id,content_id,content_type,watch_number,publication_kind,context_kind,comment) values (?,?,?,?,?,'WRITTEN_REVIEW','UNKNOWN',?)", UUID.randomUUID(), owner, content, type, pass, comment);
    }
}
