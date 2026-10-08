package com.watchwise.watchwise_api.content.dto;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
import com.watchwise.watchwise_api.content.service.ContentCardSpec;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistEntryResponseDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistPageResponseDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ContentCardDTOTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Should serialize card identity and metadata with absent optional facets as null")
    void shouldSerializeCardIdentityAndMetadataWithAbsentOptionalFacetsAsNull() throws Exception {
        UUID contentId = UUID.fromString("bd072932-47a6-4ef2-9752-475c228107d4");
        ContentCardDTO card = new ContentCardDTO(
                contentId,
                ContentType.MOVIE,
                "550",
                null,
                null,
                null,
                "Fight Club",
                "/fight-club.jpg",
                null,
                LocalDate.of(1999, 10, 15),
                1999,
                139,
                null,
                null,
                null,
                null,
                null,
                null,
                ContentPreviewStatus.AVAILABLE);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(card));

        assertThat(fieldNames(json)).containsExactlyInAnyOrder(
                "contentId", "type", "tmdbId", "seriesTmdbId", "seasonNumber", "episodeNumber",
                "title", "posterPath", "customPosterUrl", "releaseDate", "releaseYear", "runtimeMinutes",
                "totalRuntimeMinutes", "numberOfSeasons", "numberOfEpisodes", "genres", "stats", "viewerState",
                "previewStatus");
        assertThat(json.get("contentId").asString()).isEqualTo("bd072932-47a6-4ef2-9752-475c228107d4");
        assertThat(json.get("type").asString()).isEqualTo("MOVIE");
        assertThat(json.get("tmdbId").asString()).isEqualTo("550");
        assertThat(json.get("title").asString()).isEqualTo("Fight Club");
        assertThat(json.get("posterPath").asString()).isEqualTo("/fight-club.jpg");
        assertThat(json.get("releaseDate").asString()).isEqualTo("1999-10-15");
        assertThat(json.get("releaseYear").asInt()).isEqualTo(1999);
        assertThat(json.get("runtimeMinutes").asInt()).isEqualTo(139);
        assertThat(json.get("previewStatus").asString()).isEqualTo("AVAILABLE");
        assertThat(json.get("seriesTmdbId").isNull()).isTrue();
        assertThat(json.get("seasonNumber").isNull()).isTrue();
        assertThat(json.get("episodeNumber").isNull()).isTrue();
        assertThat(json.get("customPosterUrl").isNull()).isTrue();
        assertThat(json.get("totalRuntimeMinutes").isNull()).isTrue();
        assertThat(json.get("numberOfSeasons").isNull()).isTrue();
        assertThat(json.get("numberOfEpisodes").isNull()).isTrue();
        assertThat(json.get("genres").isNull()).isTrue();
        assertThat(json.get("stats").isNull()).isTrue();
        assertThat(json.get("viewerState").isNull()).isTrue();
    }

    @Test
    @DisplayName("Should preserve the existing Watchlist response contract beside card contracts")
    void shouldPreserveExistingWatchlistResponseContractBesideCardContracts() throws Exception {
        UUID contentId = UUID.fromString("bd072932-47a6-4ef2-9752-475c228107d4");
        UUID entryId = UUID.fromString("c261c2aa-25de-47e8-a82b-06544f0647a5");
        ContentRefDTO content = new ContentRefDTO(
                contentId, "550", ContentType.MOVIE, null, null, null, null, null,
                LocalDateTime.of(2026, 1, 2, 3, 4), LocalDateTime.of(2026, 1, 3, 4, 5));
        WatchlistEntryResponseDTO entry = new WatchlistEntryResponseDTO(
                entryId, ContentType.MOVIE, content, 2,
                LocalDateTime.of(2026, 1, 2, 3, 4), LocalDateTime.of(2026, 1, 3, 4, 5),
                LocalDate.of(2026, 2, 1));
        WatchlistPageResponseDTO response = new WatchlistPageResponseDTO(
                List.of(entry), 3, 20, 41, 3, false, 7);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));
        JsonNode serializedEntry = json.get("content").get(0);
        JsonNode serializedContent = serializedEntry.get("content");

        assertThat(fieldNames(json)).containsExactlyInAnyOrder(
                "content", "page", "size", "totalElements", "totalPages", "hasNext", "upcomingCount");
        assertThat(json.get("page").asInt()).isEqualTo(3);
        assertThat(json.get("size").asInt()).isEqualTo(20);
        assertThat(json.get("totalElements").asLong()).isEqualTo(41);
        assertThat(json.get("totalPages").asInt()).isEqualTo(3);
        assertThat(json.get("hasNext").asBoolean()).isFalse();
        assertThat(json.get("upcomingCount").asLong()).isEqualTo(7);
        assertThat(fieldNames(serializedEntry)).containsExactlyInAnyOrder(
                "id", "type", "content", "position", "createdAt", "updatedAt", "releaseDate");
        assertThat(serializedEntry.get("id").asString()).isEqualTo("c261c2aa-25de-47e8-a82b-06544f0647a5");
        assertThat(serializedEntry.get("position").asInt()).isEqualTo(2);
        assertThat(serializedEntry.get("releaseDate").asString()).isEqualTo("2026-02-01");
        assertThat(fieldNames(serializedContent)).containsExactlyInAnyOrder(
                "id", "tmdbId", "type", "seriesTmdbId", "seasonNumber", "episodeNumber", "isSeasonFinale",
                "isSeriesFinale", "createdAt", "updatedAt", "runtimeMinutes", "totalRuntimeMinutes", "genres",
                "releaseYear", "countries");
        assertThat(serializedContent.get("id").asString()).isEqualTo("bd072932-47a6-4ef2-9752-475c228107d4");
        assertThat(serializedContent.get("tmdbId").asString()).isEqualTo("550");
        assertThat(fieldNames(serializedContent)).doesNotContain("stats", "viewerState", "previewStatus");
    }

    @Test
    @DisplayName("Should retain content-card identity and context contract values")
    void shouldRetainContentCardIdentityAndContextContractValues() {
        ContentCoordinate coordinate = new ContentCoordinate(ContentType.EPISODE, null, "1399", 1, 2);
        ContentCardSpec spec = new ContentCardSpec(
                coordinate, "Pilot", "/pilot.jpg", LocalDate.of(2026, 1, 2), 50);
        UUID posterUserId = UUID.fromString("bd072932-47a6-4ef2-9752-475c228107d4");
        UUID viewerId = UUID.fromString("c261c2aa-25de-47e8-a82b-06544f0647a5");
        ContentCardContext context = new ContentCardContext("pt-BR", "BR", posterUserId, viewerId);

        assertThat(spec.coordinate()).isEqualTo(coordinate);
        assertThat(spec.title()).isEqualTo("Pilot");
        assertThat(spec.posterPath()).isEqualTo("/pilot.jpg");
        assertThat(spec.releaseDate()).isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(spec.runtimeMinutes()).isEqualTo(50);
        assertThat(context.language()).isEqualTo("pt-BR");
        assertThat(context.region()).isEqualTo("BR");
        assertThat(context.posterUserId()).isEqualTo(posterUserId);
        assertThat(context.viewerId()).isEqualTo(viewerId);
    }

    @Test
    @DisplayName("Should expose only the supported selective card facets")
    void shouldExposeOnlySupportedSelectiveCardFacets() {
        assertThat(ContentCardFieldSet.values()).containsExactly(
                ContentCardFieldSet.BASIC_METADATA,
                ContentCardFieldSet.STATS,
                ContentCardFieldSet.VIEWER_STATE,
                ContentCardFieldSet.WATCHLIST_PROGRESS,
                ContentCardFieldSet.SOCIAL_METADATA);
    }

    private Set<String> fieldNames(JsonNode json) {
        return new HashSet<>(json.propertyNames());
    }
}
