package com.watchwise.watchwise_api.content.controller;

import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.content.dto.ContentDetailsDTO;
import com.watchwise.watchwise_api.content.dto.ContentChildCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentListMembershipDTO;
import com.watchwise.watchwise_api.content.dto.ContentNavigationDTO;
import com.watchwise.watchwise_api.content.dto.ContentPageDTO;
import com.watchwise.watchwise_api.content.dto.ContentPageMetadataDTO;
import com.watchwise.watchwise_api.content.dto.ContentPageSectionsDTO;
import com.watchwise.watchwise_api.content.dto.ContentPageStatsDTO;
import com.watchwise.watchwise_api.content.dto.ContentPageWatchProviderDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.dto.ContentStatsResponseDTO;
import com.watchwise.watchwise_api.content.dto.RatingDistributionDTO;
import com.watchwise.watchwise_api.content.dto.WatchStatus;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.content.service.ContentDetailsService;
import com.watchwise.watchwise_api.content.service.ContentPageService;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.content.service.ContentStatsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentControllerTest {

    @Mock
    private ContentService contentService;

    @Mock
    private ContentStatsService contentStatsService;

    @Mock
    private ContentDetailsService contentDetailsService;

    @Mock
    private ContentPageService contentPageService;

    @Mock
    private RequestThrottler requestThrottler;

    @InjectMocks
    private ContentController contentController;

    private UUID currentUserId;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        currentUserId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUserId, null, List.of())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Return Ok With ContentRefDTO - When Service Resolves The Reference")
    void shouldReturnOkWithContentRefDtoWhenServiceResolvesTheReference() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("550", ContentType.MOVIE, null, null, null, null, null);
        ContentRefDTO responseDto = new ContentRefDTO(
                UUID.randomUUID(), "550", ContentType.MOVIE, null, null, null, null, null, LocalDateTime.now(), LocalDateTime.now()
        );
        when(contentService.getOrCreateReference(dto)).thenReturn(responseDto);

        ResponseEntity<ContentRefDTO> result = contentController.getOrCreateReference(dto);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(responseDto);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Delegate To Service With The Same DTO - When Called")
    void shouldDelegateToServiceWithTheSameDtoWhenCalled() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.SEASON, "1399", 1, null, null, null);
        ContentRefDTO responseDto = new ContentRefDTO(
                UUID.randomUUID(), null, ContentType.SEASON, "1399", 1, null, null, null, LocalDateTime.now(), LocalDateTime.now()
        );
        when(contentService.getOrCreateReference(dto)).thenReturn(responseDto);

        contentController.getOrCreateReference(dto);

        verify(contentService).getOrCreateReference(dto);
    }

    @Test
    @DisplayName("[getStats] Should Return Ok With Stats - When Service Resolves Them")
    void shouldReturnOkWithStatsWhenServiceResolvesThem() {
        UUID contentId = UUID.randomUUID();
        ContentStatsResponseDTO stats = new ContentStatsResponseDTO(contentId, 8.2, 10, 4, 6);
        when(contentStatsService.getStats(contentId)).thenReturn(stats);

        ResponseEntity<ContentStatsResponseDTO> result = contentController.getStats(contentId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(stats);
    }

    @Test
    @DisplayName("[getStatsBatch] Should Return Ok With The List From The Service - When Called With Multiple Ids")
    void shouldReturnOkWithTheListFromTheServiceWhenCalledWithMultipleIds() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        List<ContentStatsResponseDTO> stats = List.of(
                new ContentStatsResponseDTO(first, 8.2, 10, 4, 6),
                new ContentStatsResponseDTO(second, null, 0, 0, 0));
        when(contentStatsService.getStatsBatch(List.of(first, second))).thenReturn(stats);

        ResponseEntity<List<ContentStatsResponseDTO>> result = contentController.getStatsBatch(List.of(first, second));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(stats);
    }

    @Test
    @DisplayName("[getDetails] Should Return Ok With Details Resolved For The Current User - When Service Resolves Them")
    void shouldReturnOkWithDetailsResolvedForTheCurrentUserWhenServiceResolvesThem() {
        UUID contentId = UUID.randomUUID();
        ContentDetailsDTO details = new ContentDetailsDTO(
                contentId, ContentType.MOVIE, "The Matrix", null, null, null, null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null, List.of(), List.of(), List.of());
        when(contentDetailsService.getDetails(contentId, currentUserId, null)).thenReturn(details);

        ResponseEntity<ContentDetailsDTO> result = contentController.getDetails(contentId, null);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(details);
        verify(contentDetailsService).getDetails(contentId, currentUserId, null);
    }

    @Test
    @DisplayName("[getDetails] Should Forward An Explicit Poster User - When Provided")
    void shouldForwardAnExplicitPosterUserWhenProvided() {
        UUID contentId = UUID.randomUUID();
        UUID posterUserId = UUID.randomUUID();
        ContentDetailsDTO details = new ContentDetailsDTO(
                contentId, ContentType.MOVIE, "The Matrix", null, null, null, null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null, List.of(), List.of(), List.of());
        when(contentDetailsService.getDetails(contentId, currentUserId, posterUserId)).thenReturn(details);

        ResponseEntity<ContentDetailsDTO> result = contentController.getDetails(contentId, posterUserId);

        assertThat(result.getBody()).isEqualTo(details);
        verify(contentDetailsService).getDetails(contentId, currentUserId, posterUserId);
    }

    @Test
    @DisplayName("[getDetailsBatch] Should Return Ok With The List From The Service Resolved For The Current User - When Called With Multiple Ids")
    void shouldReturnOkWithTheListFromTheServiceResolvedForTheCurrentUserWhenCalledWithMultipleIds() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ContentDetailsDTO firstDetails = new ContentDetailsDTO(
                first, ContentType.MOVIE, "First", null, null, null, null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null, List.of(), List.of(), List.of());
        ContentDetailsDTO secondDetails = new ContentDetailsDTO(
                second, ContentType.MOVIE, "Second", null, null, null, null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null, List.of(), List.of(), List.of());
        when(contentDetailsService.getDetailsBatch(List.of(first, second), currentUserId, null))
                .thenReturn(List.of(firstDetails, secondDetails));

        ResponseEntity<List<ContentDetailsDTO>> result = contentController.getDetailsBatch(List.of(first, second), null);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).containsExactly(firstDetails, secondDetails);
    }

    @Test
    @DisplayName("[getDetailsBatch] Should Forward An Explicit Poster User - When Provided")
    void shouldForwardAnExplicitPosterUserForTheBatchWhenProvided() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID posterUserId = UUID.randomUUID();
        List<ContentDetailsDTO> details = List.of(
                new ContentDetailsDTO(first, ContentType.MOVIE, "First", null, null, null, null, null, null, null, null,
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        null, null, List.of(), List.of(), List.of()),
                new ContentDetailsDTO(second, ContentType.MOVIE, "Second", null, null, null, null, null, null, null, null,
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        null, null, List.of(), List.of(), List.of()));
        when(contentDetailsService.getDetailsBatch(List.of(first, second), currentUserId, posterUserId))
                .thenReturn(details);

        ResponseEntity<List<ContentDetailsDTO>> result =
                contentController.getDetailsBatch(List.of(first, second), posterUserId);

        assertThat(result.getBody()).containsExactlyElementsOf(details);
        verify(contentDetailsService).getDetailsBatch(List.of(first, second), currentUserId, posterUserId);
    }

    @Test
    @DisplayName("[getPage] Should Return Ok With The Page For The Current User - When Service Resolves It")
    void shouldReturnOkWithThePageForTheCurrentUserWhenServiceResolvesIt() {
        UUID contentId = UUID.randomUUID();
        ContentPageDTO page = new ContentPageDTO(null, null, null, 0, null, null, null);
        when(contentPageService.getPage(contentId, currentUserId)).thenReturn(page);

        ResponseEntity<ContentPageDTO> result = contentController.getPage(contentId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(page);
        verify(contentPageService).getPage(contentId, currentUserId);
    }

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
                new com.watchwise.watchwise_api.content.dto.ContentViewerStateDTO(
                        WatchStatus.PARTIALLY_WATCHED, 9, "A review", LocalDate.of(2026, 1, 3), 2,
                        true, 2, UUID.randomUUID(), true, UUID.randomUUID(), false, null, null, 1, 10),
                new ContentNavigationDTO("1399", 1, 2, 10, null, null),
                new ContentPageSectionsDTO(List.of(child), null, List.of()));

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(page));

        assertThat(fieldNames(json)).containsExactlyInAnyOrder(
                "details", "metadata", "stats", "visibleReviewsCount", "viewerState", "navigation", "sections");
        assertThat(fieldNames(json.get("metadata"))).containsExactlyInAnyOrder(
                "originalLanguage", "certification", "homepageUrl", "tmdbUrl", "imdbUrl", "watchProviders");
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
                new com.watchwise.watchwise_api.content.dto.ContentViewerStateDTO(
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
