package com.watchwise.watchwise_api.content.dto;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ContentPageDTOTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Should Serialize The Content Page With Exact Names And Empty Collections")
    void shouldSerializeTheContentPageWithExactNamesAndEmptyCollections() throws Exception {
        UUID contentId = UUID.randomUUID();
        ContentChildCardDTO child = new ContentChildCardDTO(
                null, ContentType.EPISODE, null, "1399", 1, 2, "Pilot", null,
                LocalDate.of(2026, 1, 2), 50, null, null);
        ContentPageDTO page = new ContentPageDTO(
                minimalDetails(contentId),
                new ContentPageMetadataDTO("en", "PG-13", "https://watchwise.test", "https://tmdb.test/550",
                        "https://imdb.test/tt0133093", null),
                new ContentPageStatsDTO(contentId, 8.5, 2, null, 12, 3),
                4,
                new ContentViewerStateDTO(
                        WatchStatus.PARTIALLY_WATCHED, 9, "A review", LocalDate.of(2026, 1, 3), 2,
                        true, 2, UUID.randomUUID(), true, UUID.randomUUID(), false, null, null, 1, 10),
                new ContentNavigationDTO("1399", 1, 2, 10, null, null),
                new ContentPageSectionsDTO(List.of(child), null, List.of()));

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(page));

        assertThat(fieldNames(json)).containsExactlyInAnyOrder(
                "details", "metadata", "stats", "visibleReviewsCount", "viewerState", "navigation", "sections");
        assertThat(fieldNames(json.get("metadata"))).containsExactlyInAnyOrder(
                "originalLanguage", "certification", "homepageUrl", "tmdbUrl", "imdbUrl", "watchProviders",
                "presentationPosterPath", "presentationCrew", "crewInherited");
        assertThat(fieldNames(json.get("stats"))).containsExactlyInAnyOrder(
                "contentId", "averageScore", "ratingsCount", "ratingsDistribution", "playsCount", "commentsCount");
        assertThat(fieldNames(json.get("viewerState"))).containsExactlyInAnyOrder(
                "watchStatus", "myRating", "myReview", "lastWatchedDate", "lastWatchNumber", "watchedInTheater",
                "playsCount", "latestDiaryEntryId", "inWatchlist", "watchlistEntryId", "dropped", "droppedEntryId",
                "lists", "watchedEpisodeCount", "releasedEpisodeCount");
        assertThat(fieldNames(json.get("navigation"))).containsExactlyInAnyOrder(
                "seriesTmdbId", "seasonNumber", "episodeNumber", "seasonEpisodeCount", "previousEpisode", "nextEpisode");
        assertThat(fieldNames(json.get("sections"))).containsExactlyInAnyOrder(
                "seasons", "episodes", "recentEpisodes");
        assertThat(fieldNames(json.get("sections").get("seasons").get(0))).containsExactlyInAnyOrder(
                "contentId", "type", "tmdbId", "seriesTmdbId", "seasonNumber", "episodeNumber", "title",
                "posterPath", "releaseDate", "runtimeMinutes", "stats", "viewerState");
        assertThat(json.get("sections").get("seasons").get(0).get("contentId").isNull()).isTrue();
        assertThat(json.get("metadata").get("watchProviders")).isEmpty();
        assertThat(json.get("stats").get("ratingsDistribution")).isEmpty();
        assertThat(json.get("viewerState").get("lists")).isEmpty();
        assertThat(json.get("sections").get("episodes")).isEmpty();
        assertThat(json.get("sections").get("recentEpisodes")).isEmpty();
    }

    @Test
    @DisplayName("Should Serialize Page Metadata Providers And Rating Distribution With Exact Names")
    void shouldSerializePageMetadataProvidersAndRatingDistributionWithExactNames() throws Exception {
        ContentPageDTO page = new ContentPageDTO(
                null,
                new ContentPageMetadataDTO(null, null, null, null, null,
                        List.of(new ContentPageWatchProviderDTO(8, "Netflix", "/netflix.png", "flatrate", "https://netflix.test"))),
                new ContentPageStatsDTO(null, null, 1,
                        List.of(new RatingDistributionDTO(8, 1)), 2, 3),
                0,
                new ContentViewerStateDTO(
                        WatchStatus.UNKNOWN, null, null, null, null, null, null, null, false, null,
                        false, null, List.of(new ContentListMembershipDTO(UUID.randomUUID(), "Favorites", UserListVisibility.PUBLIC)),
                        null, null),
                null,
                new ContentPageSectionsDTO(List.of(), List.of(), List.of()));

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(page));

        assertThat(fieldNames(json.get("metadata").get("watchProviders").get(0))).containsExactlyInAnyOrder(
                "providerId", "providerName", "logoPath", "type", "watchUrl");
        assertThat(fieldNames(json.get("stats").get("ratingsDistribution").get(0)))
                .containsExactlyInAnyOrder("score", "count");
        assertThat(fieldNames(json.get("viewerState").get("lists").get(0)))
                .containsExactlyInAnyOrder("listId", "name", "visibility");
    }

    @Test
    @DisplayName("Should Preserve The Existing Generic Content DTO JSON Shapes")
    void shouldPreserveTheExistingGenericContentDtoJsonShapes() throws Exception {
        JsonNode details = objectMapper.readTree(objectMapper.writeValueAsString(minimalDetails(UUID.randomUUID())));
        JsonNode stats = objectMapper.readTree(objectMapper.writeValueAsString(
                new ContentStatsResponseDTO(UUID.randomUUID(), null, 0, 0, 0)));

        assertThat(fieldNames(details)).containsExactlyInAnyOrder(
                "contentId", "type", "title", "overview", "posterPath", "backdropPath", "releaseDate",
                "runtimeMinutes", "totalRuntimeMinutes", "numberOfSeasons", "numberOfEpisodes", "genres",
                "countries", "cast", "guestStars", "creators", "watchProviders", "seasons", "episodes",
                "recentEpisodes", "budget", "revenue", "productionCompanies", "crew", "videos", "imdb_id",
                "facebook_id", "instagram_id", "twitter_id", "customPosterUrl", "parentTitle", "parentReleaseYear");
        assertThat(fieldNames(details)).doesNotContain(
                "presentationPosterPath", "presentationCrew", "crewInherited");
        assertThat(fieldNames(stats)).containsExactlyInAnyOrder(
                "contentId", "averageScore", "playsCount", "reviewsCount", "commentsCount");
    }

    private ContentDetailsDTO minimalDetails(UUID contentId) {
        return new ContentDetailsDTO(
                contentId, ContentType.MOVIE, "The Matrix", null, null, null, null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null, List.of(), List.of(), List.of());
    }

    private Set<String> fieldNames(JsonNode json) {
        return new HashSet<>(json.propertyNames());
    }
}
