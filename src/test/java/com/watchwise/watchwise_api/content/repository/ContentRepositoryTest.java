package com.watchwise.watchwise_api.content.repository;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    @DisplayName("[findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue] Should Return The Finale Episode - When It Exists")
    void shouldReturnTheFinaleEpisodeWhenItExists() {
        contentRepository.save(buildEpisode("1399", 1, 1));
        Content finale = contentRepository.save(buildFinaleEpisode("1399", 1, 8));

        Optional<Content> result = contentRepository
                .findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("1399", 1, ContentType.EPISODE);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(finale.getId());
        assertThat(result.get().getEpisodeNumber()).isEqualTo(8);
    }

    @Test
    @DisplayName("[findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue] Should Return Empty - When No Episode Is Flagged As The Finale")
    void shouldReturnEmptyWhenNoEpisodeIsFlaggedAsTheFinale() {
        contentRepository.save(buildEpisode("1399", 1, 1));

        Optional<Content> result = contentRepository
                .findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("1399", 1, ContentType.EPISODE);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue] Should Return The Finale Season - When It Exists")
    void shouldReturnTheFinaleSeasonWhenItExists() {
        contentRepository.save(buildSeason("1399", 1));
        Content finaleSeason = contentRepository.save(buildFinaleSeason("1399", 3));

        Optional<Content> result = contentRepository
                .findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("1399", ContentType.SEASON);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(finaleSeason.getId());
        assertThat(result.get().getSeasonNumber()).isEqualTo(3);
    }

    @Test
    @DisplayName("[findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue] Should Return Empty - When No Season Is Flagged As The Series Finale")
    void shouldReturnEmptyWhenNoSeasonIsFlaggedAsTheSeriesFinale() {
        contentRepository.save(buildSeason("1399", 1));

        Optional<Content> result = contentRepository
                .findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("1399", ContentType.SEASON);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When A Second Episode Is Marked As The Same Season's Finale")
    void shouldThrowDataIntegrityViolationExceptionWhenASecondEpisodeIsMarkedAsTheSameSeasonsFinale() {
        contentRepository.saveAndFlush(buildFinaleEpisode("1399", 1, 8));

        assertThatThrownBy(() -> contentRepository.saveAndFlush(buildFinaleEpisode("1399", 1, 9)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When A Second Season Is Marked As The Series Finale")
    void shouldThrowDataIntegrityViolationExceptionWhenASecondSeasonIsMarkedAsTheSeriesFinale() {
        contentRepository.saveAndFlush(buildFinaleSeason("1399", 3));

        assertThatThrownBy(() -> contentRepository.saveAndFlush(buildFinaleSeason("1399", 4)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When An Episode Is Saved With EpisodeNumber Zero")
    void shouldThrowDataIntegrityViolationExceptionWhenAnEpisodeIsSavedWithEpisodeNumberZero() {
        assertThatThrownBy(() -> contentRepository.saveAndFlush(buildEpisode("1399", 1, 0)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_contents_episode_number");
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When A Season Is Saved With A Negative SeasonNumber")
    void shouldThrowDataIntegrityViolationExceptionWhenASeasonIsSavedWithANegativeSeasonNumber() {
        assertThatThrownBy(() -> contentRepository.saveAndFlush(buildSeason("1399", -1)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_contents_season_number");
    }

    @Test
    @DisplayName("[save] Should Persist The Season - When SeasonNumber Is Zero")
    void shouldPersistTheSeasonWhenSeasonNumberIsZero() {
        Content specials = contentRepository.saveAndFlush(buildSeason("1399", 0));

        assertThat(contentRepository.findById(specials.getId())).isPresent();
        assertThat(specials.getSeasonNumber()).isZero();
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When A Movie Is Saved With IsSeasonFinale Set")
    void shouldThrowDataIntegrityViolationExceptionWhenAMovieIsSavedWithIsSeasonFinaleSet() {
        LocalDateTime now = LocalDateTime.now();
        Content movie = Content.builder()
                .tmdbId("550")
                .type(ContentType.MOVIE)
                .isSeasonFinale(true)
                .createdAt(now)
                .updatedAt(now)
                .build();

        assertThatThrownBy(() -> contentRepository.saveAndFlush(movie))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_contents_finale_flags_by_type");
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When A Series Is Saved With IsSeriesFinale Set")
    void shouldThrowDataIntegrityViolationExceptionWhenASeriesIsSavedWithIsSeriesFinaleSet() {
        LocalDateTime now = LocalDateTime.now();
        Content series = Content.builder()
                .tmdbId("1399")
                .type(ContentType.SERIES)
                .isSeriesFinale(true)
                .createdAt(now)
                .updatedAt(now)
                .build();

        assertThatThrownBy(() -> contentRepository.saveAndFlush(series))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_contents_finale_flags_by_type");
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When A Season Is Saved With IsSeasonFinale Set")
    void shouldThrowDataIntegrityViolationExceptionWhenASeasonIsSavedWithIsSeasonFinaleSet() {
        LocalDateTime now = LocalDateTime.now();
        Content season = Content.builder()
                .seriesTmdbId("1399")
                .seasonNumber(1)
                .type(ContentType.SEASON)
                .isSeasonFinale(true)
                .createdAt(now)
                .updatedAt(now)
                .build();

        assertThatThrownBy(() -> contentRepository.saveAndFlush(season))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_contents_finale_flags_by_type");
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

    private Content buildFinaleEpisode(String seriesTmdbId, Integer seasonNumber, Integer episodeNumber) {
        LocalDateTime now = LocalDateTime.now();
        return Content.builder()
                .seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber)
                .episodeNumber(episodeNumber)
                .type(ContentType.EPISODE)
                .isSeasonFinale(true)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private Content buildFinaleSeason(String seriesTmdbId, Integer seasonNumber) {
        LocalDateTime now = LocalDateTime.now();
        return Content.builder()
                .seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber)
                .type(ContentType.SEASON)
                .isSeriesFinale(true)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
