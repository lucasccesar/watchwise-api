package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.platformimport.entity.LetterboxdSeedLoad;
import com.watchwise.watchwise_api.platformimport.model.AliasTrust;
import com.watchwise.watchwise_api.platformimport.parser.CsvRecordReader;
import com.watchwise.watchwise_api.platformimport.repository.ExternalContentAliasRepository;
import com.watchwise.watchwise_api.platformimport.repository.LetterboxdSeedLoadRepository;
import com.watchwise.watchwise_api.platformimport.service.ImportIdentityNormalizer;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Component
@ConditionalOnProperty(prefix = "app.import.letterboxd-seed", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LetterboxdSeedLoader implements ApplicationRunner {
    static final String RESOURCE_LOCATION = "classpath:import/letterboxd-filmes-e-series-sem-especiais-ajustado.csv";
    static final String SEED_VERSION = "letterboxd-catalog-v1";
    static final String PROVENANCE = "letterboxd-filmes-e-series-sem-especiais-ajustado.csv";
    static final String EXPECTED_SHA256 = "3f74b2a21a97576b58f9f51661838edfd63a372aaa7d80bf5a866e7750c47726";
    private static final int BATCH_SIZE = 500;
    private static final List<String> EXPECTED_HEADER = List.of(
            "title", "release_year", "tmdb_media_type", "tmdb_id", "series_tmdb_id", "season_number", "episode_number");
    private static final String INSERT_ALIAS_SQL = """
            insert into external_content_aliases(id, user_id, scope_key, target_kind, original_title,
                normalized_title, release_year, target_key, target_type, target_tmdb_id, series_tmdb_id,
                season_number, episode_number, person_tmdb_id, provenance, trust, seed_version, created_at, updated_at)
            values (?, null, 'GLOBAL', 'CONTENT', ?, ?, ?, ?, ?, ?, ?, ?, ?, null, ?, 'CURATED_SEED', ?, ?, ?)
            on conflict do nothing
            """;
    private static final String INSERT_LOAD_SQL = """
            insert into letterboxd_seed_loads(id, seed_version, checksum, loaded_at)
            values (?, ?, ?, ?)
            on conflict (seed_version, checksum) do nothing
            """;

    private final CsvRecordReader csvRecordReader;
    private final ImportIdentityNormalizer identityNormalizer;
    private final ExternalContentAliasRepository aliasRepository;
    private final LetterboxdSeedLoadRepository seedLoadRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ResourceLoader resourceLoader;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public LetterboxdSeedLoader(
            CsvRecordReader csvRecordReader,
            ImportIdentityNormalizer identityNormalizer,
            ExternalContentAliasRepository aliasRepository,
            LetterboxdSeedLoadRepository seedLoadRepository,
            JdbcTemplate jdbcTemplate,
            ResourceLoader resourceLoader,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.csvRecordReader = csvRecordReader;
        this.identityNormalizer = identityNormalizer;
        this.aliasRepository = aliasRepository;
        this.seedLoadRepository = seedLoadRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.resourceLoader = resourceLoader;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        loadSeed();
    }

    public void loadSeed() {
        byte[] csv = readResource();
        String checksum = sha256(csv);
        if (!EXPECTED_SHA256.equals(checksum)) {
            throw new IllegalStateException("Letterboxd catalog fingerprint does not match the curated resource");
        }
        List<SeedAlias> aliases = parse(csv);
        LocalDateTime loadedAt = LocalDateTime.now(clock);
        transactionTemplate.executeWithoutResult(status -> {
            if (seedLoadRepository.findBySeedVersionAndChecksum(SEED_VERSION, checksum).isPresent()) return;
            aliasRepository.deleteByProvenanceAndTrust(PROVENANCE, AliasTrust.CURATED_SEED);
            insertAliases(aliases, loadedAt);
            jdbcTemplate.update(INSERT_LOAD_SQL, UUID.randomUUID(), SEED_VERSION, checksum, Timestamp.valueOf(loadedAt));
        });
    }

    private byte[] readResource() {
        Resource resource = resourceLoader.getResource(RESOURCE_LOCATION);
        try (InputStream input = resource.getInputStream()) {
            return input.readAllBytes();
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read the curated Letterboxd catalog", ex);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private List<SeedAlias> parse(byte[] csv) {
        List<SeedAlias> aliases = new ArrayList<>();
        boolean[] headerSeen = {false};
        try (InputStreamReader reader = new InputStreamReader(new ByteArrayInputStream(csv), StandardCharsets.UTF_8)) {
            csvRecordReader.read(reader, row -> {
                if (!headerSeen[0]) {
                    if (!EXPECTED_HEADER.equals(row)) throw new IllegalStateException("Letterboxd catalog header does not match the expected format");
                    headerSeen[0] = true;
                    return;
                }
                if (row.size() != EXPECTED_HEADER.size()) throw new IllegalStateException("Letterboxd catalog row has an unexpected column count");
                aliases.add(toAlias(row));
            });
        } catch (IOException ex) {
            throw new IllegalStateException("Could not parse the curated Letterboxd catalog", ex);
        }
        if (!headerSeen[0] || aliases.isEmpty()) throw new IllegalStateException("Letterboxd catalog is empty");
        return aliases;
    }

    private SeedAlias toAlias(List<String> row) {
        String title = row.get(0).strip();
        String normalizedTitle = identityNormalizer.normalizeTitle(title);
        if (title.isEmpty() || title.length() > 500 || normalizedTitle == null || normalizedTitle.isEmpty()) {
            throw new IllegalStateException("Letterboxd catalog contains an invalid title");
        }
        Integer year = parseOptionalPositiveInteger(row.get(1), "release year");
        String tmdbId = blankToNull(row.get(3));
        String seriesTmdbId = blankToNull(row.get(4));
        Integer seasonNumber = parseOptionalNonNegativeInteger(row.get(5), "season number");
        Integer episodeNumber = parseOptionalPositiveInteger(row.get(6), "episode number");
        String mediaType = row.get(2).strip().toLowerCase(java.util.Locale.ROOT);
        ContentType targetType;
        String targetTmdbId = null;
        String targetKey;
        switch (mediaType) {
            case "movie" -> {
                requireTmdbId(tmdbId);
                targetType = ContentType.MOVIE;
                targetTmdbId = tmdbId;
                targetKey = "CONTENT|MOVIE|" + tmdbId;
                requireEmpty(seriesTmdbId, seasonNumber, episodeNumber);
            }
            case "tv" -> {
                requireTmdbId(tmdbId);
                targetType = ContentType.SERIES;
                targetTmdbId = tmdbId;
                targetKey = "CONTENT|SERIES|" + tmdbId;
                requireEmpty(seriesTmdbId, seasonNumber, episodeNumber);
            }
            case "season" -> {
                requireTmdbId(seriesTmdbId);
                if (seasonNumber == null || episodeNumber != null || tmdbId != null) {
                    throw new IllegalStateException("Letterboxd season row has invalid coordinates");
                }
                targetType = ContentType.SEASON;
                targetKey = "CONTENT|SEASON|" + seriesTmdbId + "|" + seasonNumber;
            }
            case "episode" -> {
                requireTmdbId(seriesTmdbId);
                if (seasonNumber == null || episodeNumber == null || tmdbId != null) {
                    throw new IllegalStateException("Letterboxd episode row has invalid coordinates");
                }
                targetType = ContentType.EPISODE;
                targetKey = "CONTENT|EPISODE|" + seriesTmdbId + "|" + seasonNumber + "|" + episodeNumber;
            }
            default -> throw new IllegalStateException("Letterboxd catalog contains unsupported media type");
        }
        return new SeedAlias(title, normalizedTitle, year, targetType, targetTmdbId, seriesTmdbId,
                seasonNumber, episodeNumber, targetKey);
    }

    private void insertAliases(List<SeedAlias> aliases, LocalDateTime loadedAt) {
        for (int offset = 0; offset < aliases.size(); offset += BATCH_SIZE) {
            List<SeedAlias> batch = aliases.subList(offset, Math.min(offset + BATCH_SIZE, aliases.size()));
            jdbcTemplate.batchUpdate(INSERT_ALIAS_SQL, batch, batch.size(), (statement, alias) -> {
                statement.setObject(1, UUID.randomUUID());
                statement.setString(2, alias.originalTitle());
                statement.setString(3, alias.normalizedTitle());
                if (alias.releaseYear() == null) statement.setNull(4, Types.INTEGER);
                else statement.setInt(4, alias.releaseYear());
                statement.setString(5, alias.targetKey());
                statement.setString(6, alias.targetType().name());
                setNullableString(statement, 7, alias.targetTmdbId());
                setNullableString(statement, 8, alias.seriesTmdbId());
                if (alias.seasonNumber() == null) statement.setNull(9, Types.INTEGER);
                else statement.setInt(9, alias.seasonNumber());
                if (alias.episodeNumber() == null) statement.setNull(10, Types.INTEGER);
                else statement.setInt(10, alias.episodeNumber());
                statement.setString(11, PROVENANCE);
                statement.setString(12, SEED_VERSION);
                statement.setTimestamp(13, Timestamp.valueOf(loadedAt));
                statement.setTimestamp(14, Timestamp.valueOf(loadedAt));
            });
        }
    }

    private void setNullableString(java.sql.PreparedStatement statement, int index, String value) throws java.sql.SQLException {
        if (value == null) statement.setNull(index, Types.VARCHAR);
        else statement.setString(index, value);
    }

    private Integer parseOptionalPositiveInteger(String value, String label) {
        if (value == null || value.isBlank()) return null;
        try {
            int parsed = Integer.parseInt(value.strip());
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Letterboxd catalog contains an invalid " + label, ex);
        }
    }

    private Integer parseOptionalNonNegativeInteger(String value, String label) {
        if (value == null || value.isBlank()) return null;
        try {
            int parsed = Integer.parseInt(value.strip());
            if (parsed < 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Letterboxd catalog contains an invalid " + label, ex);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private void requireTmdbId(String id) {
        if (id == null || !id.matches("[1-9][0-9]*")) throw new IllegalStateException("Letterboxd catalog contains an invalid TMDB id");
    }

    private void requireEmpty(String seriesId, Integer season, Integer episode) {
        if (seriesId != null || season != null || episode != null) {
            throw new IllegalStateException("Letterboxd movie/series row contains episode coordinates");
        }
    }

    private record SeedAlias(
            String originalTitle,
            String normalizedTitle,
            Integer releaseYear,
            ContentType targetType,
            String targetTmdbId,
            String seriesTmdbId,
            Integer seasonNumber,
            Integer episodeNumber,
            String targetKey) {
    }
}
