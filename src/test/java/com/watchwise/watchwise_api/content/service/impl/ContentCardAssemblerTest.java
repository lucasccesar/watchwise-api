package com.watchwise.watchwise_api.content.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.watchwise.watchwise_api.common.tmdb.*;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardFieldSet;
import com.watchwise.watchwise_api.content.dto.ContentCardStatsDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardViewerStateDTO;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.content.dto.ContentViewerStateDTO;
import com.watchwise.watchwise_api.content.dto.WatchStatus;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
import com.watchwise.watchwise_api.content.service.ContentCardSpec;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.ContentStateResolver;
import com.watchwise.watchwise_api.content.service.ContentStatsService;
import com.watchwise.watchwise_api.content.service.ContentViewerStateService;
import com.watchwise.watchwise_api.contentposter.service.UserContentPosterService;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.dropped.repository.DroppedEntryRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListItemRepository;
import com.watchwise.watchwise_api.watchlist.repository.WatchlistEntryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class ContentCardAssemblerTest {

    private static final UUID VIEWER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_VIEWER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID POSTER_USER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock
    private ContentStatsService contentStatsService;

    @Mock
    private ContentViewerStateService contentViewerStateService;

    @Mock
    private TmdbCardMetadataResolver tmdbCardMetadataResolver;

    @Mock
    private UserContentPosterService userContentPosterService;

    @Test
    @DisplayName("[assemble] Should Deduplicate Coordinates Before Resolving Any Facet")
    void shouldDeduplicateCoordinatesBeforeResolvingAnyFacet() {
        ContentCoordinate coordinate = movie("550");
        UUID contentId = UUID.randomUUID();
        ContentCardSpec first = new ContentCardSpec(coordinate, "First title", null, null, null);
        ContentCardSpec duplicate = new ContentCardSpec(coordinate, "Second title", null, null, null);
        when(contentViewerStateService.resolve(
                isNull(), eq(List.of(coordinate)), eq(Map.of())))
                .thenReturn(resolution(coordinate, contentId, emptyState()));
        when(tmdbCardMetadataResolver.resolve(coordinate, "en-US"))
                .thenReturn(new TmdbLookupResult.Found<>(
                        new TmdbCardMetadata("TMDB title", "/poster.jpg", "2026-01-02", 120)));

        ContentCardAssembler assembler = assembler();

        Map<ContentCoordinate, ContentCardDTO> result = assembler.assemble(
                List.of(first, duplicate),
                new ContentCardContext("en-US", "BR", null, null),
                Set.of(ContentCardFieldSet.BASIC_METADATA));

        assertThat(result).containsOnlyKeys(coordinate);
        assertThat(result.get(coordinate).title()).isEqualTo("First title");
        verify(contentViewerStateService).resolve(isNull(), eq(List.of(coordinate)), eq(Map.of()));
        verify(tmdbCardMetadataResolver).resolve(coordinate, "en-US");
    }

    @Test
    @DisplayName("[assemble] Should Resolve Only Basic Metadata - When Basic Metadata Is Requested")
    void shouldResolveOnlyBasicMetadataWhenBasicMetadataIsRequested() {
        ContentCoordinate coordinate = movie("603");
        when(contentViewerStateService.resolve(isNull(), eq(List.of(coordinate)), eq(Map.of())))
                .thenReturn(resolution(coordinate, UUID.randomUUID(), emptyState()));
        when(tmdbCardMetadataResolver.resolve(coordinate, "pt-BR"))
                .thenReturn(new TmdbLookupResult.Found<>(
                        new TmdbCardMetadata("The Matrix", "/matrix.jpg", "1999-03-30", 136)));

        ContentCardDTO card = assembler().assemble(
                List.of(new ContentCardSpec(coordinate, null, null, null, null)),
                new ContentCardContext("pt-BR", "BR", POSTER_USER_ID, null),
                Set.of(ContentCardFieldSet.BASIC_METADATA)).get(coordinate);

        assertThat(card.title()).isEqualTo("The Matrix");
        assertThat(card.posterPath()).isEqualTo("/matrix.jpg");
        assertThat(card.releaseDate()).isEqualTo(LocalDate.of(1999, 3, 30));
        assertThat(card.runtimeMinutes()).isEqualTo(136);
        assertThat(card.stats()).isNull();
        assertThat(card.viewerState()).isNull();
        verifyNoInteractions(contentStatsService, userContentPosterService);
        verify(contentViewerStateService).resolve(isNull(), eq(List.of(coordinate)), eq(Map.of()));
    }

    @Test
    @DisplayName("[assemble] Should Resolve Public Stats In One Batch - When Stats Are Requested")
    void shouldResolvePublicStatsInOneBatchWhenStatsAreRequested() {
        ContentCoordinate coordinate = movie("550");
        UUID contentId = UUID.randomUUID();
        when(contentViewerStateService.resolve(isNull(), eq(List.of(coordinate)), eq(Map.of())))
                .thenReturn(resolution(coordinate, contentId, emptyState()));
        when(contentStatsService.getStatsBatch(List.of(contentId)))
                .thenReturn(List.of(new com.watchwise.watchwise_api.content.dto.ContentStatsResponseDTO(
                        contentId, 8.5, 12, 3, 4)));

        ContentCardDTO card = assembler().assemble(
                List.of(new ContentCardSpec(coordinate, "Fight Club", "/fight-club.jpg",
                        LocalDate.of(1999, 10, 15), 139)),
                new ContentCardContext(null, null, null, null),
                Set.of(ContentCardFieldSet.STATS)).get(coordinate);

        assertThat(card.stats()).isEqualTo(new ContentCardStatsDTO(8.5, 12L, 3L, 4L));
        assertThat(card.viewerState()).isNull();
        verify(contentStatsService).getStatsBatch(List.of(contentId));
        verifyNoInteractions(tmdbCardMetadataResolver, userContentPosterService);
    }

    @Test
    @DisplayName("[assemble] Should Resolve Custom Posters In One Batch - When Social Metadata Is Requested")
    void shouldResolveCustomPostersInOneBatchWhenSocialMetadataIsRequested() {
        ContentCoordinate coordinate = movie("550");
        UUID contentId = UUID.randomUUID();
        when(contentViewerStateService.resolve(isNull(), eq(List.of(coordinate)), eq(Map.of())))
                .thenReturn(resolution(coordinate, contentId, emptyState()));
        when(userContentPosterService.findByUserAndContentIds(POSTER_USER_ID, List.of(contentId)))
                .thenReturn(Map.of(contentId, "/custom-poster.jpg"));

        ContentCardDTO card = assembler().assemble(
                List.of(new ContentCardSpec(coordinate, "Fight Club", "/fight-club.jpg",
                        LocalDate.of(1999, 10, 15), 139)),
                new ContentCardContext(null, null, POSTER_USER_ID, null),
                Set.of(ContentCardFieldSet.SOCIAL_METADATA)).get(coordinate);

        assertThat(card.customPosterUrl()).isEqualTo("/custom-poster.jpg");
        assertThat(card.stats()).isNull();
        assertThat(card.viewerState()).isNull();
        verify(userContentPosterService).findByUserAndContentIds(POSTER_USER_ID, List.of(contentId));
        verifyNoInteractions(contentStatsService, tmdbCardMetadataResolver);
    }

    @Test
    @DisplayName("[assemble] Should Keep Viewer State Isolated By Viewer Context")
    void shouldKeepViewerStateIsolatedByViewerContext() {
        ContentCoordinate coordinate = movie("550");
        ContentViewerStateDTO firstState = state(8, true);
        ContentViewerStateDTO secondState = state(4, false);
        when(contentViewerStateService.resolve(VIEWER_ID, List.of(coordinate), Map.of()))
                .thenReturn(resolution(coordinate, UUID.randomUUID(), firstState));
        when(contentViewerStateService.resolve(OTHER_VIEWER_ID, List.of(coordinate), Map.of()))
                .thenReturn(resolution(coordinate, UUID.randomUUID(), secondState));

        ContentCardSpec spec = new ContentCardSpec(coordinate, "Fight Club", "/fight-club.jpg",
                LocalDate.of(1999, 10, 15), 139);
        ContentCardAssembler assembler = assembler();

        ContentCardDTO first = assembler.assemble(
                List.of(spec), new ContentCardContext(null, null, null, VIEWER_ID),
                Set.of(ContentCardFieldSet.VIEWER_STATE, ContentCardFieldSet.WATCHLIST_PROGRESS)).get(coordinate);
        ContentCardDTO second = assembler.assemble(
                List.of(spec), new ContentCardContext(null, null, null, OTHER_VIEWER_ID),
                Set.of(ContentCardFieldSet.VIEWER_STATE, ContentCardFieldSet.WATCHLIST_PROGRESS)).get(coordinate);

        assertThat(first.viewerState()).extracting(ContentCardViewerStateDTO::myRating,
                ContentCardViewerStateDTO::inWatchlist).containsExactly(8, true);
        assertThat(second.viewerState()).extracting(ContentCardViewerStateDTO::myRating,
                ContentCardViewerStateDTO::inWatchlist).containsExactly(4, false);
        verify(contentViewerStateService).resolve(VIEWER_ID, List.of(coordinate), Map.of());
        verify(contentViewerStateService).resolve(OTHER_VIEWER_ID, List.of(coordinate), Map.of());
        verifyNoInteractions(contentStatsService, tmdbCardMetadataResolver, userContentPosterService);
    }

    @Test
    @DisplayName("[assemble] Should Not Resolve Viewer State - When Only Basic Metadata Is Requested")
    void shouldNotResolveViewerStateWhenOnlyBasicMetadataIsRequested() {
        ContentCoordinate coordinate = movie("550");
        ContentCardSpec spec = new ContentCardSpec(coordinate, null, null, null, null);
        when(contentViewerStateService.resolve(isNull(), eq(List.of(coordinate)), eq(Map.of())))
                .thenReturn(new ContentViewerStateService.Resolution(Map.of(coordinate, emptyState()), Map.of()));
        when(tmdbCardMetadataResolver.resolve(coordinate, "en-US"))
                .thenReturn(new TmdbLookupResult.Found<>(
                        new TmdbCardMetadata("Fight Club", "/fight-club.jpg", "1999-10-15", 139)));

        ContentCardAssembler assembler = assembler();
        ContentCardDTO card = assembler.assemble(
                List.of(spec), new ContentCardContext("en-US", "US", null, VIEWER_ID),
                Set.of(ContentCardFieldSet.BASIC_METADATA)).get(coordinate);

        assertThat(card.title()).isEqualTo("Fight Club");
        assertThat(card.viewerState()).isNull();
        verify(contentViewerStateService)
                .resolve(isNull(), eq(List.of(coordinate)), eq(Map.of()));
        verifyNoInteractions(contentStatsService, userContentPosterService);
    }

    @Test
    @DisplayName("[assemble] Should Reuse Canonical TMDB Metadata Across Viewers And Isolate Viewer State")
    void shouldReuseCanonicalTmdbMetadataAcrossViewersAndIsolateViewerState() {
        RestClient.Builder restClientBuilder = RestClient.builder().baseUrl("https://api.themoviedb.org/3");
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        TmdbClient tmdbClient = new TmdbClient(
                restClientBuilder.build(),
                cache(), cache(), cache(), cache(), cache(), cache(), cache(), cache(), cache(), cache(),
                cache(), cache(), cache(), cache(), cache(), cache(), cache(), cache(), cache());
        TmdbCardMetadataResolver realResolver = new TmdbCardMetadataResolver(tmdbClient);

        ContentCoordinate coordinate = movie("550");
        UUID contentId = UUID.randomUUID();
        ContentViewerStateDTO firstState = state(8, true);
        ContentViewerStateDTO secondState = state(4, false);
        when(contentViewerStateService.resolve(VIEWER_ID, List.of(coordinate), Map.of()))
                .thenReturn(resolution(coordinate, contentId, firstState));
        when(contentViewerStateService.resolve(OTHER_VIEWER_ID, List.of(coordinate), Map.of()))
                .thenReturn(resolution(coordinate, contentId, secondState));
        mockServer.expect(requestTo("https://api.themoviedb.org/3/movie/550?language=en-US"))
                .andRespond(withSuccess("""
                        {"id":550,"title":"Fight Club","poster_path":"/fight-club.jpg",
                         "release_date":"1999-10-15","runtime":139}
                        """, MediaType.APPLICATION_JSON));

        ContentCardAssembler assembler = new ContentCardAssembler(
                contentStatsService, contentViewerStateService, realResolver, userContentPosterService);
        ContentCardSpec spec = new ContentCardSpec(coordinate, null, null, null, null);
        ContentCardDTO first = assembler.assemble(
                List.of(spec), new ContentCardContext("en-US", "US", null, VIEWER_ID),
                Set.of(ContentCardFieldSet.BASIC_METADATA, ContentCardFieldSet.VIEWER_STATE)).get(coordinate);
        ContentCardDTO second = assembler.assemble(
                List.of(spec), new ContentCardContext("en-US", "US", null, OTHER_VIEWER_ID),
                Set.of(ContentCardFieldSet.BASIC_METADATA, ContentCardFieldSet.VIEWER_STATE)).get(coordinate);

        assertThat(first.title()).isEqualTo("Fight Club");
        assertThat(second.title()).isEqualTo(first.title());
        assertThat(first.viewerState()).extracting(ContentCardViewerStateDTO::myRating)
                .isEqualTo(8);
        assertThat(second.viewerState()).extracting(ContentCardViewerStateDTO::myRating)
                .isEqualTo(4);
        mockServer.verify();
    }

    @Test
    @DisplayName("[assemble] Should Return Missing Local References Without Loading Other Facets")
    void shouldReturnMissingLocalReferencesWithoutLoadingOtherFacets() {
        ContentCoordinate coordinate = movie("404");
        when(contentViewerStateService.resolve(isNull(), eq(List.of(coordinate)), eq(Map.of())))
                .thenReturn(new ContentViewerStateService.Resolution(Map.of(coordinate, emptyState()), Map.of()));

        ContentCardDTO card = assembler().assemble(
                List.of(new ContentCardSpec(coordinate, "Unavailable locally", "/poster.jpg",
                        LocalDate.of(2026, 1, 2), 90)),
                new ContentCardContext(null, null, POSTER_USER_ID, null),
                Set.of(ContentCardFieldSet.STATS, ContentCardFieldSet.SOCIAL_METADATA)).get(coordinate);

        assertThat(card.contentId()).isNull();
        assertThat(card.stats()).isEqualTo(new ContentCardStatsDTO(null, 0L, 0L, 0L));
        assertThat(card.customPosterUrl()).isNull();
        verifyNoInteractions(contentStatsService, userContentPosterService, tmdbCardMetadataResolver);
    }

    @Test
    @DisplayName("[assemble] Should Preserve Other Cards When One TMDB Lookup Is Unavailable")
    void shouldPreserveOtherCardsWhenOneTmdbLookupIsUnavailable() {
        ContentCoordinate partialCoordinate = movie("1");
        ContentCoordinate unavailableCoordinate = movie("2");
        when(contentViewerStateService.resolve(isNull(), eq(List.of(partialCoordinate, unavailableCoordinate)), eq(Map.of())))
                .thenReturn(new ContentViewerStateService.Resolution(
                        Map.of(partialCoordinate, emptyState(), unavailableCoordinate, emptyState()), Map.of()));
        when(tmdbCardMetadataResolver.resolve(partialCoordinate, "en-US"))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbCardMetadata("One", null, null, null)));
        when(tmdbCardMetadataResolver.resolve(unavailableCoordinate, "en-US"))
                .thenReturn(new TmdbLookupResult.Unavailable<>());

        Map<ContentCoordinate, ContentCardDTO> result = assembler().assemble(
                List.of(
                        new ContentCardSpec(partialCoordinate, null, null, null, null),
                        new ContentCardSpec(unavailableCoordinate, null, null, null, null)),
                new ContentCardContext("en-US", "US", null, null),
                Set.of(ContentCardFieldSet.BASIC_METADATA));

        assertThat(result).hasSize(2);
        assertThat(result.get(partialCoordinate).previewStatus()).isEqualTo(ContentPreviewStatus.PARTIAL);
        assertThat(result.get(partialCoordinate).title()).isEqualTo("One");
        assertThat(result.get(unavailableCoordinate).previewStatus()).isEqualTo(ContentPreviewStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("[assemble] Should Never Save Content References During A Read")
    void shouldNeverSaveContentReferencesDuringARead() {
        ContentCoordinate coordinate = movie("550");
        UUID contentId = UUID.randomUUID();
        ContentRepository contentRepository = org.mockito.Mockito.mock(ContentRepository.class);
        DiaryEntryRepository diaryEntryRepository = org.mockito.Mockito.mock(DiaryEntryRepository.class);
        WatchlistEntryRepository watchlistEntryRepository = org.mockito.Mockito.mock(WatchlistEntryRepository.class);
        DroppedEntryRepository droppedEntryRepository = org.mockito.Mockito.mock(DroppedEntryRepository.class);
        UserListItemRepository userListItemRepository = org.mockito.Mockito.mock(UserListItemRepository.class);
        Content content = Content.builder().id(contentId).type(ContentType.MOVIE).tmdbId("550").build();
        when(contentRepository.findAllByCoordinates(List.of(coordinate))).thenReturn(List.of(content));
        ContentViewerStateService readOnlyViewerStateService = new ContentViewerStateServiceImpl(
                diaryEntryRepository,
                watchlistEntryRepository,
                droppedEntryRepository,
                userListItemRepository,
                contentRepository,
                new ContentStateResolver(),
                Clock.systemUTC());
        ContentCardAssembler readOnlyAssembler = new ContentCardAssembler(
                contentStatsService, readOnlyViewerStateService, tmdbCardMetadataResolver, userContentPosterService);

        readOnlyAssembler.assemble(
                List.of(new ContentCardSpec(coordinate, "Fight Club", "/fight-club.jpg",
                        LocalDate.of(1999, 10, 15), 139)),
                new ContentCardContext(null, null, null, null),
                Set.of(ContentCardFieldSet.BASIC_METADATA));

        verify(contentRepository).findAllByCoordinates(List.of(coordinate));
        verify(contentRepository, never()).save(any(Content.class));
        verifyNoInteractions(diaryEntryRepository, watchlistEntryRepository, droppedEntryRepository,
                userListItemRepository, contentStatsService, tmdbCardMetadataResolver, userContentPosterService);
    }

    private ContentCardAssembler assembler() {
        return new ContentCardAssembler(
                contentStatsService, contentViewerStateService, tmdbCardMetadataResolver, userContentPosterService);
    }

    private static <K, V> Cache<K, V> cache() {
        return Caffeine.newBuilder().build();
    }

    private ContentCoordinate movie(String tmdbId) {
        return new ContentCoordinate(ContentType.MOVIE, tmdbId, null, null, null);
    }

    private ContentViewerStateService.Resolution resolution(
            ContentCoordinate coordinate, UUID contentId, ContentViewerStateDTO state) {
        return new ContentViewerStateService.Resolution(Map.of(coordinate, state), Map.of(coordinate, contentId));
    }

    private ContentViewerStateDTO emptyState() {
        return state(null, false);
    }

    private ContentViewerStateDTO state(Integer rating, boolean inWatchlist) {
        return new ContentViewerStateDTO(
                WatchStatus.WATCHED, rating, "Review", LocalDate.of(2026, 1, 1), 1, false,
                2, UUID.randomUUID(), inWatchlist, UUID.randomUUID(), false, null, List.of(), 1, 5);
    }
}
