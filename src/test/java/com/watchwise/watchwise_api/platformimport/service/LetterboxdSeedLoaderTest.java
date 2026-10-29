package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.platformimport.service.impl.LetterboxdSeedLoader;
import com.watchwise.watchwise_api.platformimport.service.impl.ExternalContentAliasServiceImpl;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.platformimport.model.AliasQuery;
import com.watchwise.watchwise_api.platformimport.model.AliasResolution;
import com.watchwise.watchwise_api.platformimport.parser.CsvRecordReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({LetterboxdSeedLoader.class, ExternalContentAliasServiceImpl.class, CsvRecordReader.class,
        ImportIdentityNormalizer.class, ImportServiceTestConfiguration.class})
class LetterboxdSeedLoaderTest {
    private static final int EXPECTED_ROWS = 10297;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired LetterboxdSeedLoader loader;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExternalContentAliasService aliasService;
    @MockitoBean TmdbClient tmdbClient;

    @BeforeEach
    void clearSeedData() {
        jdbc.execute("TRUNCATE letterboxd_seed_loads, external_content_aliases CASCADE");
    }

    @Test
    @DisplayName("[loadSeed] Should Load Curated Catalog Once - When The Same Fingerprint Is Loaded Repeatedly")
    void shouldLoadCuratedCatalogOnceWhenSameFingerprintIsLoadedRepeatedly() {
        loader.loadSeed();
        loader.loadSeed();

        assertThat(jdbc.queryForObject("select count(*) from external_content_aliases where scope_key = 'GLOBAL'", Integer.class))
                .isEqualTo(EXPECTED_ROWS);
        assertThat(jdbc.queryForObject("select count(*) from letterboxd_seed_loads", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select checksum from letterboxd_seed_loads", String.class))
                .isEqualTo("9d8442f656d874ce1768ae44564a1e30efc95c6bf8b4cece483ab3885195c447");
        assertThat(jdbc.queryForObject("select count(*) from external_content_aliases where trust = 'CURATED_SEED'", Integer.class))
                .isEqualTo(EXPECTED_ROWS);
    }

    @Test
    @DisplayName("[loadSeed] Should Preserve Curated Leo Mapping - When Loading The Supplied Catalog")
    void shouldPreserveCuratedLeoMappingWhenLoadingSuppliedCatalog() {
        loader.loadSeed();

        assertThat(jdbc.queryForList("""
                select target_tmdb_id from external_content_aliases
                where normalized_title = 'leo' and release_year = 2023 and target_type = 'MOVIE'
                """, String.class)).containsExactly("1075794");
    }

    @Test
    @DisplayName("[loadSeed] Should Map Episode To Parent Coordinates - When Loading Episode Rows")
    void shouldMapEpisodeToParentCoordinatesWhenLoadingEpisodeRows() {
        loader.loadSeed();

        assertThat(jdbc.query("""
                select series_tmdb_id, season_number, episode_number from external_content_aliases
                where normalized_title = 'decalogue i' and target_type = 'EPISODE'
                """, (resultSet, rowNumber) -> List.of(
                resultSet.getString("series_tmdb_id"),
                resultSet.getInt("season_number"),
                resultSet.getInt("episode_number")))).containsExactly(List.of("42699", 1, 1));
    }

    @Test
    @DisplayName("[resolveBatch] Should Resolve A Large Curated Catalog Batch - When Ten Thousand Aliases Are Loaded")
    void shouldResolveLargeCuratedCatalogBatchWhenTenThousandAliasesAreLoaded() {
        loader.loadSeed();
        List<AliasQuery> queries = jdbc.query("""
                select original_title, release_year, target_type from external_content_aliases
                where trust = 'CURATED_SEED' and release_year is not null
                order by id limit 200
                """, (resultSet, rowNumber) -> new AliasQuery(
                resultSet.getString("original_title"),
                resultSet.getInt("release_year"),
                ContentType.valueOf(resultSet.getString("target_type"))));

        long startedAt = System.nanoTime();
        List<AliasResolution> results = aliasService.resolveBatch(UUID.randomUUID(), queries);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        System.out.printf("A4 alias lookup benchmark: %d queries over %d aliases in %d ms%n",
                queries.size(), EXPECTED_ROWS, elapsedMillis);
        assertThat(results).hasSize(queries.size()).allMatch(result -> result.status() != AliasResolution.Status.UNAVAILABLE);
        verifyNoInteractions(tmdbClient);
    }
}
