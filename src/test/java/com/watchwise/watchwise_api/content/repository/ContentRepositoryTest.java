package com.watchwise.watchwise_api.content.repository;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class ContentRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private ContentRepository contentRepository;

    @BeforeEach
    void setUp() {
        contentRepository.deleteAll();
    }

    @Test
    @DisplayName("[findByTmdbIdAndType] Should Return Content - When Movie With Matching TmdbId And Type Exists")
    void shouldReturnContentWhenMovieWithMatchingTmdbIdAndTypeExists() {
        contentRepository.save(buildMovie("550"));

        Optional<Content> result = contentRepository.findByTmdbIdAndType("550", ContentType.MOVIE);

        assertThat(result).isPresent();
        assertThat(result.get().getTmdbId()).isEqualTo("550");
    }

    @Test
    @DisplayName("[findByTmdbIdAndType] Should Return Empty - When TmdbId Matches But Type Does Not")
    void shouldReturnEmptyWhenTmdbIdMatchesButTypeDoesNot() {
        contentRepository.save(buildMovie("550"));

        Optional<Content> result = contentRepository.findByTmdbIdAndType("550", ContentType.SERIES);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[findByTmdbIdAndType] Should Return Empty - When No Content Matches TmdbId")
    void shouldReturnEmptyWhenNoContentMatchesTmdbId() {
        Optional<Content> result = contentRepository.findByTmdbIdAndType("999", ContentType.MOVIE);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType] Should Return Content - When Season With Null EpisodeNumber Matches")
    void shouldReturnContentWhenSeasonWithNullEpisodeNumberMatches() {
        contentRepository.save(buildSeason("1399", 1));

        Optional<Content> result = contentRepository
                .findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("1399", 1, null, ContentType.SEASON);

        assertThat(result).isPresent();
        assertThat(result.get().getSeriesTmdbId()).isEqualTo("1399");
        assertThat(result.get().getSeasonNumber()).isEqualTo(1);
        assertThat(result.get().getEpisodeNumber()).isNull();
    }

    @Test
    @DisplayName("[findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType] Should Return Content - When Episode With Matching EpisodeNumber Matches")
    void shouldReturnContentWhenEpisodeWithMatchingEpisodeNumberMatches() {
        contentRepository.save(buildEpisode("1399", 1, 3));

        Optional<Content> result = contentRepository
                .findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("1399", 1, 3, ContentType.EPISODE);

        assertThat(result).isPresent();
        assertThat(result.get().getEpisodeNumber()).isEqualTo(3);
    }

    @Test
    @DisplayName("[findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType] Should Not Match Season Row - When Querying With A Non-Null EpisodeNumber")
    void shouldNotMatchSeasonRowWhenQueryingWithANonNullEpisodeNumber() {
        contentRepository.save(buildSeason("1399", 1));

        Optional<Content> result = contentRepository
                .findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("1399", 1, 3, ContentType.EPISODE);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType] Should Not Match Episode Row - When Querying With A Null EpisodeNumber")
    void shouldNotMatchEpisodeRowWhenQueryingWithANullEpisodeNumber() {
        contentRepository.save(buildEpisode("1399", 1, 3));

        Optional<Content> result = contentRepository
                .findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("1399", 1, null, ContentType.SEASON);

        assertThat(result).isEmpty();
    }

    private Content buildMovie(String tmdbId) {
        LocalDateTime now = LocalDateTime.now();
        return Content.builder()
                .tmdbId(tmdbId)
                .type(ContentType.MOVIE)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private Content buildSeason(String seriesTmdbId, Integer seasonNumber) {
        LocalDateTime now = LocalDateTime.now();
        return Content.builder()
                .seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber)
                .type(ContentType.SEASON)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private Content buildEpisode(String seriesTmdbId, Integer seasonNumber, Integer episodeNumber) {
        LocalDateTime now = LocalDateTime.now();
        return Content.builder()
                .seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber)
                .episodeNumber(episodeNumber)
                .type(ContentType.EPISODE)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
