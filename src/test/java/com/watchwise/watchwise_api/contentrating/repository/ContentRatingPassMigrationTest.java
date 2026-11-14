package com.watchwise.watchwise_api.contentrating.repository;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ContentRatingPassMigrationTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void copiesCurrentRatingIntoUnassociatedLegacySnapshotWhenUpgradingToV98() {
        String schema = "rating_pass_migration_" + UUID.randomUUID().toString().replace("-", "");
        flyway(schema, "97").migrate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        UUID ownerId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        UUID ratingId = UUID.randomUUID();
        String qualified = schema + ".";

        jdbc.update("insert into " + qualified
                        + "users(id,username,email,password,profile_picture,name) values (?,?,?,'hash','picture','Test')",
                ownerId, ownerId.toString(), ownerId + "@example.com");
        jdbc.update("insert into " + qualified + "contents(id,type,tmdb_id) values (?,'SERIES','tv-legacy')",
                contentId);
        jdbc.update("insert into " + qualified
                        + "content_ratings(id,user_id,content_id,content_type,score) values (?,?,?,'SERIES',9)",
                ratingId, ownerId, contentId);

        flyway(schema, null).migrate();

        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables "
                        + "where table_schema = ? and table_name = 'content_rating_passes'",
                Integer.class, schema))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select watch_number from " + qualified
                        + "content_ratings where id = ?", Integer.class, ratingId))
                .isNull();
        assertThat(jdbc.queryForObject("select count(*) from " + qualified
                        + "content_rating_passes where user_id = ? and content_id = ? and watch_number is null "
                        + "and score = 9", Integer.class, ownerId, contentId))
                .isEqualTo(1);
    }

    private Flyway flyway(String schema, String targetVersion) {
        var configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration");
        if (targetVersion != null) {
            configuration.target(MigrationVersion.fromVersion(targetVersion));
        }
        return configuration.load();
    }

    private DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
