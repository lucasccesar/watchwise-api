package com.watchwise.watchwise_api.summary.service.impl;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.watchwise.watchwise_api.common.dto.GenreCountDTO;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardViewerStateDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardFieldSet;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
import com.watchwise.watchwise_api.content.service.ContentCardSpec;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.impl.ContentCardAssembler;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.summary.dto.ProfileHighlightContentDTO;
import com.watchwise.watchwise_api.summary.dto.ProfileDiaryPreviewDTO;
import com.watchwise.watchwise_api.summary.dto.ProfileRewatchKind;
import com.watchwise.watchwise_api.summary.dto.ProfileTop5EntryDTO;
import com.watchwise.watchwise_api.summary.dto.RatingCountDTO;
import com.watchwise.watchwise_api.summary.dto.RecentActivityItemDTO;
import com.watchwise.watchwise_api.summary.dto.RecentActivitySource;
import com.watchwise.watchwise_api.summary.dto.RecentActivityStatus;
import com.watchwise.watchwise_api.summary.dto.SummaryResponseDTO;
import com.watchwise.watchwise_api.summary.dto.WatchTimeDTO;
import com.watchwise.watchwise_api.summary.service.ProfileDisplayMetadataResolver;
import com.watchwise.watchwise_api.summary.service.ProfileSummaryDataReader;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anySet;

@ExtendWith(MockitoExtension.class)
class ProfileSummaryReaderImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private FollowerRepository followerRepository;

    @Mock
    private ProfileSummaryDataReader dataReader;

    @Mock
    private ProfileDisplayMetadataResolver displayMetadataResolver;

    @Mock
    private ContentCardAssembler contentCardAssembler;

    private ProfileSummaryReaderImpl reader;
    private UUID viewerId;
    private UUID userId;
    private User target;

    private final ObjectMapper objectMapper = new ObjectMapper();


    @BeforeEach
    void setUp() {
        reader = new ProfileSummaryReaderImpl(
                userRepository, followerRepository, dataReader, displayMetadataResolver, contentCardAssembler);
        viewerId = UUID.randomUUID();
        userId = UUID.randomUUID();
        target = User.builder().id(userId).isProfilePublic(true).build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(target));
    }

    @Test
    void shouldAssembleSummaryAndComputeRatingsSummaryFromDistinctDistribution() {
        Content movie = Content.builder().id(UUID.randomUUID()).type(ContentType.MOVIE).tmdbId("550").build();
        ProfileSummaryDataReader.Snapshot snapshot = new ProfileSummaryDataReader.Snapshot(
                new WatchTimeDTO(500L, 120L, 4L, 1L),
                List.of(new GenreCountDTO("Drama", 2L)),
                List.of(new RatingCountDTO(8, 2L), new RatingCountDTO(10, 1L)),
                List.of(), List.of(), List.of(),
                new ProfileSummaryDataReader.RewatchData(ProfileRewatchKind.MOST_REWATCHED, movie, 3L),
                null);
        when(dataReader.read(viewerId, userId, ContentType.MOVIE)).thenReturn(snapshot);
        ProfileHighlightContentDTO highlight = new ProfileHighlightContentDTO(
                ContentType.MOVIE, movie.getId(), "550", null, "Fight Club", 1999, "/poster.jpg", null);
        when(displayMetadataResolver.resolveStoredContent(userId, movie)).thenReturn(highlight);

        SummaryResponseDTO result = reader.read(viewerId, userId, ContentType.MOVIE);

        assertThat(result.watchTime().totalWatchedCount()).isEqualTo(4L);
        assertThat(result.ratingsSummary().totalRatings()).isEqualTo(3L);
        assertThat(result.ratingsSummary().averageScore()).isEqualTo(26.0 / 3.0);
        assertThat(result.highlights().rewatch().content()).isEqualTo(highlight);
        assertThat(result.genreCounts()).containsExactly(new GenreCountDTO("Drama", 2L));
    }

    @Test
    void shouldReturnVisualCardsForRecentProfileRowsWithoutStatsOrViewerState() {
        ContentRefDTO content = new ContentRefDTO(
                UUID.randomUUID(), "550", ContentType.MOVIE, null, null, null,
                false, false, null, null);
        ProfileDiaryPreviewDTO preview = new ProfileDiaryPreviewDTO(
                UUID.randomUUID(), content, 9, java.time.LocalDate.of(2026, 10, 5), 1, null, List.of());
        ProfileSummaryDataReader.Snapshot snapshot = new ProfileSummaryDataReader.Snapshot(
                new WatchTimeDTO(0L, 0L, 0L, 0L), List.of(), List.of(),
                List.of(preview), List.of(), List.of(), null, null);
        when(dataReader.read(viewerId, userId, ContentType.MOVIE)).thenReturn(snapshot);
        ContentCoordinate coordinate = new ContentCoordinate(ContentType.MOVIE, "550", null, null, null);
        ContentCardDTO card = new ContentCardDTO(
                content.id(), ContentType.MOVIE, "550", null, null, null, "Fight Club", "/fight-club.jpg",
                "/custom-fight-club.jpg", java.time.LocalDate.of(1999, 10, 15), 1999, 139,
                null, null, null, null, null, null, ContentPreviewStatus.AVAILABLE);
        when(contentCardAssembler.assemble(anyCollection(), any(ContentCardContext.class), anySet()))
                .thenReturn(java.util.Map.of(coordinate, card));

        SummaryResponseDTO result = reader.read(viewerId, userId, ContentType.MOVIE);

        JsonNode previewCard = serialize(result).get("recentEpisodes").get(0).get("card");
        assertThat(previewCard.get("title").asString()).isEqualTo("Fight Club");
        assertThat(previewCard.get("posterPath").asString()).isEqualTo("/fight-club.jpg");
        assertThat(previewCard.get("stats").isNull()).isTrue();
        assertThat(previewCard.get("viewerState").isNull()).isTrue();

        ArgumentCaptor<ContentCardContext> context = ArgumentCaptor.forClass(ContentCardContext.class);
        ArgumentCaptor<Set<ContentCardFieldSet>> fields = ArgumentCaptor.forClass(Set.class);
        verify(contentCardAssembler).assemble(anyCollection(), context.capture(), fields.capture());
        assertThat(context.getValue().posterUserId()).isEqualTo(userId);
        assertThat(context.getValue().viewerId()).isNull();
        assertThat(fields.getValue()).containsExactlyInAnyOrder(
                ContentCardFieldSet.BASIC_METADATA, ContentCardFieldSet.SOCIAL_METADATA);
    }

    @Test
    void shouldResolveTopFiveCardsInTheSharedProfileCardBatch() {
        ContentRefDTO content = new ContentRefDTO(
                UUID.randomUUID(), "550", ContentType.MOVIE, null, null, null,
                false, false, null, null);
        ProfileTop5EntryDTO top5Entry = new ProfileTop5EntryDTO(
                UUID.randomUUID(), ContentType.MOVIE, content, 1,
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now(), null, 9, true, null);
        ProfileSummaryDataReader.Snapshot snapshot = new ProfileSummaryDataReader.Snapshot(
                new WatchTimeDTO(0L, 0L, 0L, 0L), List.of(), List.of(),
                List.of(), List.of(), List.of(), null, null, List.of(top5Entry));
        when(dataReader.read(viewerId, userId, ContentType.MOVIE)).thenReturn(snapshot);
        ContentCoordinate coordinate = new ContentCoordinate(ContentType.MOVIE, "550", null, null, null);
        ContentCardDTO card = new ContentCardDTO(
                content.id(), ContentType.MOVIE, "550", null, null, null, "Fight Club", "/fight-club.jpg",
                "/owner-poster.jpg", java.time.LocalDate.of(1999, 10, 15), 1999, 139,
                null, null, null, null, null, null, ContentPreviewStatus.AVAILABLE);
        when(contentCardAssembler.assemble(anyCollection(), any(ContentCardContext.class), anySet()))
                .thenReturn(java.util.Map.of(coordinate, card));
        ArgumentCaptor<java.util.Collection<ContentCardSpec>> specs = ArgumentCaptor.forClass(java.util.Collection.class);

        SummaryResponseDTO result = reader.read(viewerId, userId, ContentType.MOVIE);

        assertThat(result.top5()).singleElement().satisfies(item -> {
            assertThat(item.hasWatched()).isTrue();
            assertThat(item.card()).isEqualTo(card);
            assertThat(item.customPosterUrl()).isEqualTo("/owner-poster.jpg");
        });
        verify(contentCardAssembler).assemble(specs.capture(), any(ContentCardContext.class), anySet());
        assertThat(specs.getValue()).extracting(ContentCardSpec::coordinate).contains(coordinate);
    }

    @Test
    void shouldIncludeCurrentSeriesRatingAndVersionOnlyOnOwnersProfile() {
        ContentRefDTO content = new ContentRefDTO(
                UUID.randomUUID(), "1399", ContentType.SERIES, null, null, null,
                false, false, null, null);
        ProfileTop5EntryDTO top5Entry = new ProfileTop5EntryDTO(
                UUID.randomUUID(), ContentType.SERIES, content, 1,
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now(), null, 8, true, null);
        ProfileDiaryPreviewDTO reviewPreview = new ProfileDiaryPreviewDTO(
                UUID.randomUUID(), content, 7, java.time.LocalDate.now(), 1, null, List.of());
        ProfileSummaryDataReader.Snapshot snapshot = new ProfileSummaryDataReader.Snapshot(
                new WatchTimeDTO(0L, 0L, 0L, 0L), List.of(), List.of(), List.of(), List.of(reviewPreview),
                List.of(new RecentActivityItemDTO(UUID.randomUUID(), RecentActivitySource.SERIES_COMPLETION,
                        content, RecentActivityStatus.COMPLETED, 8, 1L, null,
                        java.time.LocalDateTime.now(), null)), null, null, List.of(top5Entry),
                Map.of(content.id(), new ProfileSummaryDataReader.SeriesRating(8, 3L)));
        when(dataReader.read(userId, userId, ContentType.SERIES)).thenReturn(snapshot);
        when(dataReader.read(viewerId, userId, ContentType.SERIES)).thenReturn(
                new ProfileSummaryDataReader.Snapshot(
                        new WatchTimeDTO(0L, 0L, 0L, 0L), List.of(), List.of(), List.of(), List.of(),
                        List.of(new RecentActivityItemDTO(UUID.randomUUID(), RecentActivitySource.SERIES_COMPLETION,
                                content, RecentActivityStatus.COMPLETED, 8, 1L, null,
                                java.time.LocalDateTime.now(), null)), null, null, List.of(top5Entry)));
        ContentCoordinate coordinate = new ContentCoordinate(ContentType.SERIES, "1399", null, null, null);
        ContentCardDTO card = new ContentCardDTO(
                content.id(), ContentType.SERIES, "1399", null, null, null, "Example Series", "/poster.jpg",
                null, null, null, null, null, null, null, null, null, null, ContentPreviewStatus.AVAILABLE);
        when(contentCardAssembler.assemble(anyCollection(), any(ContentCardContext.class), anySet()))
                .thenReturn(java.util.Map.of(coordinate, card));

        SummaryResponseDTO ownerResult = reader.read(userId, userId, ContentType.SERIES);
        SummaryResponseDTO visitorResult = reader.read(viewerId, userId, ContentType.SERIES);

        JsonNode ownerState = serialize(ownerResult).get("top5").get(0).get("card").get("viewerState");
        assertThat(ownerState.get("myRating").asInt()).isEqualTo(8);
        assertThat(ownerState.get("currentRatingVersion").asLong()).isEqualTo(3L);
        JsonNode activityState = serialize(ownerResult).get("recentActivity").get(0).get("card").get("viewerState");
        assertThat(activityState.get("myRating").asInt()).isEqualTo(8);
        assertThat(activityState.get("currentRatingVersion").asLong()).isEqualTo(3L);
        JsonNode reviewState = serialize(ownerResult).get("recentReviews").get(0).get("card").get("viewerState");
        assertThat(reviewState.get("myRating").asInt()).isEqualTo(8);
        assertThat(reviewState.get("currentRatingVersion").asLong()).isEqualTo(3L);
        JsonNode visitorState = serialize(visitorResult).get("top5").get(0).get("card").get("viewerState");
        assertThat(visitorState.isNull()).isTrue();
    }

    private JsonNode serialize(Object value) {
        try {
            return objectMapper.readTree(objectMapper.writeValueAsString(value));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void shouldRejectPrivateProfileBeforeReadingAggregates() {
        target.setIsProfilePublic(false);
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(viewerId, userId, FollowStatus.ACCEPTED))
                .thenReturn(false);

        assertThatThrownBy(() -> reader.read(viewerId, userId, ContentType.MOVIE))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This user profile is private");

        verify(dataReader, never()).read(any(), any(), any());
    }

    @Test
    void shouldRejectUnsupportedSummaryTypeBeforeReadingAggregates() {
        assertThatThrownBy(() -> reader.read(viewerId, userId, ContentType.EPISODE))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("type must be one of: MOVIE, SERIES");

        verify(dataReader, never()).read(any(), any(), any());
    }

    @Test
    void shouldThrowNotFoundWhenTargetDoesNotExist() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reader.read(viewerId, userId, ContentType.MOVIE))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");

        verify(dataReader, never()).read(any(), any(), any());
    }
}
