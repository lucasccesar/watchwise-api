package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.content.dto.ContentChildCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentDetailsDTO;
import com.watchwise.watchwise_api.content.dto.ContentNavigationDTO;
import com.watchwise.watchwise_api.content.dto.ContentPageDTO;
import com.watchwise.watchwise_api.content.dto.ContentPageMetadataDTO;
import com.watchwise.watchwise_api.content.dto.ContentPageSectionsDTO;
import com.watchwise.watchwise_api.content.dto.ContentPageStatsDTO;
import com.watchwise.watchwise_api.content.dto.ContentViewerStateDTO;
import com.watchwise.watchwise_api.content.dto.CrewMemberDTO;
import com.watchwise.watchwise_api.content.dto.SeasonSummaryDTO;
import com.watchwise.watchwise_api.content.dto.WatchStatus;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.ContentDetailsService;
import com.watchwise.watchwise_api.content.service.EpisodeNavigationContext;
import com.watchwise.watchwise_api.content.service.ContentPageDetails;
import com.watchwise.watchwise_api.content.service.ContentPageMetadataService;
import com.watchwise.watchwise_api.content.service.ContentPageService;
import com.watchwise.watchwise_api.content.service.ContentPageStatsService;
import com.watchwise.watchwise_api.content.service.ContentViewerStateService;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentPageServiceImplTest {

    private static final UUID VIEWER_ID = UUID.randomUUID();

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private DiaryEntryRepository diaryEntryRepository;

    @Mock
    private ContentDetailsService contentDetailsService;

    @Mock
    private ContentPageMetadataService contentPageMetadataService;

    @Mock
    private ContentPageStatsService contentPageStatsService;

    @Mock
    private ContentViewerStateService contentViewerStateService;

    @Mock
    private ContentChildCardAssembler contentChildCardAssembler;

    private ContentPageService contentPageService;

    @BeforeEach
    void setUp() {
        contentPageService = new ContentPageServiceImpl(
                contentRepository,
                userRepository,
                diaryEntryRepository,
                contentDetailsService,
                contentPageMetadataService,
                contentPageStatsService,
                contentViewerStateService,
                contentChildCardAssembler);
    }

    @Test
    @DisplayName("[getPage] Should Assemble Movie Reads With Viewer Scoped Root State")
    void shouldAssembleMovieReadsWithViewerScopedRootState() {
        UUID contentId = UUID.randomUUID();
        Content content = content(ContentType.MOVIE, contentId, "550", null, null, null);
        User viewer = viewer();
        ContentDetailsDTO details = details(contentId, ContentType.MOVIE);
        ContentPageMetadataDTO metadata = new ContentPageMetadataDTO("en", null, null, null, null, List.of());
        ContentPageStatsDTO stats = new ContentPageStatsDTO(contentId, 8.5, 1, List.of(), 2, 3);
        ContentViewerStateDTO viewerState = viewerState();
        ContentPageSectionsDTO sections = new ContentPageSectionsDTO(List.of(), List.of(), List.of());
        ContentCoordinate coordinate = ContentCoordinate.from(content);

        when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
        when(userRepository.findById(VIEWER_ID)).thenReturn(Optional.of(viewer));
        when(contentDetailsService.getDetails(contentId, VIEWER_ID)).thenReturn(details);
        when(contentPageMetadataService.getMetadata(content, viewer.getPreferredLanguage(), viewer.getPreferredRegion()))
                .thenReturn(metadata);
        when(contentPageStatsService.getStats(contentId, ContentType.MOVIE)).thenReturn(stats);
        when(contentViewerStateService.resolve(VIEWER_ID, List.of(coordinate), Map.of()))
                .thenReturn(new ContentViewerStateService.Resolution(Map.of(coordinate, viewerState), Map.of()));
        when(diaryEntryRepository.countVisibleContentReviews(contentId, VIEWER_ID)).thenReturn(4L);
        when(contentChildCardAssembler.assembleSections(details, coordinate, VIEWER_ID)).thenReturn(sections);
        when(contentChildCardAssembler.assembleNavigation(details, null, coordinate, VIEWER_ID)).thenReturn(null);

        ContentPageDTO result = contentPageService.getPage(contentId, VIEWER_ID);

        assertThat(result).isEqualTo(new ContentPageDTO(
                details, metadata, stats, 4, viewerState, null, sections));
        verify(contentViewerStateService).resolve(VIEWER_ID, List.of(coordinate), Map.of());
        verify(contentChildCardAssembler).assembleSections(details, coordinate, VIEWER_ID);
        verify(contentChildCardAssembler).assembleNavigation(details, null, coordinate, VIEWER_ID);
        verifyNoMoreInteractions(contentPageMetadataService, contentPageStatsService, contentViewerStateService,
                contentChildCardAssembler);
    }

    @Test
    @DisplayName("[getPage] Should Resolve Series Season Viewer State From Series Summaries")
    void shouldResolveSeriesSeasonViewerStateFromSeriesSummaries() {
        UUID seriesId = UUID.randomUUID();
        Content series = content(ContentType.SERIES, seriesId, "1399", null, null, null);
        ContentDetailsDTO seriesDetails = details(seriesId, ContentType.SERIES);
        ContentPageSectionsDTO sections = new ContentPageSectionsDTO(List.of(), List.of(), List.of());
        ContentCoordinate coordinate = ContentCoordinate.from(series);
        stubCommonPageReads(series, seriesDetails, coordinate);
        when(contentChildCardAssembler.assembleSectionsWithSeasonSummaries(
                seriesDetails, seriesDetails.seasons(), coordinate, VIEWER_ID, "US", "en-US"))
                .thenReturn(sections);
        when(contentChildCardAssembler.assembleNavigation(seriesDetails, null, coordinate, VIEWER_ID))
                .thenReturn(null);

        ContentPageDTO result = contentPageService.getPage(seriesId, VIEWER_ID);

        assertThat(result.sections()).isEqualTo(sections);
        verify(contentChildCardAssembler).assembleSectionsWithSeasonSummaries(
                seriesDetails, seriesDetails.seasons(), coordinate, VIEWER_ID, "US", "en-US");
        verify(contentRepository, org.mockito.Mockito.never())
                .findByTmdbIdAndType(org.mockito.ArgumentMatchers.anyString(), eq(ContentType.SERIES));
        verify(contentRepository, org.mockito.Mockito.never())
                .findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType(
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.isNull(), eq(ContentType.SEASON));
    }

    @Test
    @DisplayName("[getPage] Should Use Season Summaries Without Loading The Parent Series")
    void shouldUseSeasonSummariesWithoutLoadingTheParentSeries() {
        UUID seasonId = UUID.randomUUID();
        Content season = content(ContentType.SEASON, seasonId, null, "1399", 1, null);
        ContentDetailsDTO seasonDetails = details(seasonId, ContentType.SEASON);
        List<SeasonSummaryDTO> siblingSeasons = List.of(
                new SeasonSummaryDTO(1, "Season 1", "/season.jpg", null, 8, 8));
        ContentCoordinate coordinate = ContentCoordinate.from(season);
        stubCommonPageReads(season, seasonDetails, coordinate);
        ContentViewerStateDTO seasonViewerState = viewerState(WatchStatus.WATCHED);
        ContentChildCardDTO seasonCard = new ContentChildCardDTO(
                seasonId, ContentType.SEASON, null, "1399", 1, 8, null,
                "Season 1", null, "/season.jpg", null, null, null, seasonViewerState);
        ContentPageSectionsDTO resolvedSections = new ContentPageSectionsDTO(
                List.of(seasonCard), List.of(), List.of());
        when(contentDetailsService.getPageDetails(season, VIEWER_ID, "en-US", "US"))
                .thenReturn(new ContentPageDetails(seasonDetails, siblingSeasons));
        when(contentChildCardAssembler.assembleSectionsWithSeasonSummaries(
                seasonDetails, siblingSeasons, coordinate, VIEWER_ID, "US", "en-US"))
                .thenReturn(resolvedSections);
        when(contentChildCardAssembler.assembleNavigation(seasonDetails, null, coordinate, VIEWER_ID)).thenReturn(null);

        ContentPageDTO result = contentPageService.getPage(seasonId, VIEWER_ID);

        assertThat(result.sections()).isEqualTo(resolvedSections);
        assertThat(result.viewerState()).isEqualTo(seasonViewerState);
        org.mockito.Mockito.verifyNoInteractions(contentViewerStateService);
        verify(contentRepository, org.mockito.Mockito.never())
                .findByTmdbIdAndType("1399", ContentType.SERIES);
        verify(contentDetailsService, org.mockito.Mockito.never()).getDetails(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(contentChildCardAssembler).assembleSectionsWithSeasonSummaries(
                seasonDetails, siblingSeasons, coordinate, VIEWER_ID, "US", "en-US");
    }

    @Test
    @DisplayName("[getPage] Should Pass The Resolved Episode Navigation Context To The Assembler")
    void shouldPassResolvedEpisodeNavigationContextToAssembler() {
        UUID episodeId = UUID.randomUUID();
        Content episode = content(ContentType.EPISODE, episodeId, null, "1399", 2, 3);
        ContentDetailsDTO episodeDetails = details(episodeId, ContentType.EPISODE);
        ContentPageSectionsDTO sections = new ContentPageSectionsDTO(List.of(), List.of(), List.of());
        ContentNavigationDTO navigation = new ContentNavigationDTO("1399", 2, 3, 8, null, null);
        ContentCoordinate coordinate = ContentCoordinate.from(episode);
        stubCommonPageReads(episode, episodeDetails, coordinate);
        doReturn(new ContentPageDetails(
                        episodeDetails, List.of(), new EpisodeNavigationContext(8, null, null)))
                .when(contentDetailsService).getPageDetails(episode, VIEWER_ID, "en-US", "US");
        when(contentChildCardAssembler.assembleSections(episodeDetails, coordinate, VIEWER_ID))
                .thenReturn(sections);
        when(contentChildCardAssembler.assembleNavigation(
                org.mockito.ArgumentMatchers.any(), eq(coordinate), eq(VIEWER_ID),
                org.mockito.ArgumentMatchers.any(ContentViewerStateService.Resolution.class),
                org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(navigation);

        ContentPageDTO result = contentPageService.getPage(episodeId, VIEWER_ID);

        assertThat(result.navigation()).isEqualTo(navigation);
        verify(contentChildCardAssembler).assembleNavigation(
                org.mockito.ArgumentMatchers.any(), eq(coordinate), eq(VIEWER_ID),
                org.mockito.ArgumentMatchers.any(ContentViewerStateService.Resolution.class),
                org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    @DisplayName("[getPage] Should Expose Episode Presentation Poster And Inherited Crew Flag")
    void shouldExposeEpisodePresentationPosterAndInheritedCrewFlag() {
        UUID episodeId = UUID.randomUUID();
        UUID seasonId = UUID.randomUUID();
        Content episode = content(ContentType.EPISODE, episodeId, null, "1399", 2, 3);
        ContentDetailsDTO episodeDetails = detailsWithCrew(episodeId, ContentType.EPISODE, "/season-poster.jpg");
        ContentPageMetadataDTO metadata = new ContentPageMetadataDTO(null, null, null, null, null, List.of());
        ContentPageSectionsDTO sections = new ContentPageSectionsDTO(List.of(), List.of(), List.of());
        ContentCoordinate coordinate = ContentCoordinate.from(episode);
        stubCommonPageReads(episode, episodeDetails, coordinate);
        when(contentPageMetadataService.getMetadata(eq(episode), eq("en-US"), eq("US")))
                .thenReturn(metadata);
        when(contentChildCardAssembler.assembleSections(episodeDetails, coordinate, VIEWER_ID))
                .thenReturn(sections);

        ContentPageDTO result = contentPageService.getPage(episodeId, VIEWER_ID);

        assertThat(result.metadata().presentationPosterPath()).isEqualTo("/season-poster.jpg");
        assertThat(result.metadata().presentationCrew()).isEqualTo(episodeDetails.crew());
        assertThat(result.metadata().crewInherited()).isTrue();
    }

    @Test
    @DisplayName("[getPage] Should Return Empty Parent Navigation Without Creating Missing Parent")
    void shouldReturnEmptyParentNavigationWithoutCreatingMissingParent() {
        UUID episodeId = UUID.randomUUID();
        Content episode = content(ContentType.EPISODE, episodeId, null, "1399", 2, 3);
        ContentDetailsDTO episodeDetails = details(episodeId, ContentType.EPISODE, "/episode-still.jpg");
        ContentPageSectionsDTO sections = new ContentPageSectionsDTO(List.of(), List.of(), List.of());
        ContentCoordinate coordinate = ContentCoordinate.from(episode);
        stubCommonPageReads(episode, episodeDetails, coordinate);
        when(contentChildCardAssembler.assembleSections(episodeDetails, coordinate, VIEWER_ID))
                .thenReturn(sections);

        ContentPageDTO result = contentPageService.getPage(episodeId, VIEWER_ID);

        assertThat(result.navigation()).isNull();
        assertThat(result.sections()).isEqualTo(sections);
        verify(contentRepository, org.mockito.Mockito.never())
                .save(org.mockito.ArgumentMatchers.any(Content.class));
    }

    private void stubCommonPageReads(
            Content content, ContentDetailsDTO details, ContentCoordinate coordinate) {
        when(contentRepository.findById(content.getId())).thenReturn(Optional.of(content));
        when(userRepository.findById(VIEWER_ID)).thenReturn(Optional.of(viewer()));
        if (content.getType() == ContentType.EPISODE) {
            lenient().when(contentDetailsService.getPageDetails(
                    content, VIEWER_ID, "en-US", "US"))
                    .thenReturn(new ContentPageDetails(details, List.of()));
        } else if (content.getType() != ContentType.SEASON) {
            when(contentDetailsService.getPageDetails(
                    content, VIEWER_ID, "en-US", "US"))
                    .thenReturn(new ContentPageDetails(details, List.of()));
        }
        lenient().when(contentPageMetadataService.getMetadata(eq(content), eq("en-US"), eq("US")))
                .thenReturn(new ContentPageMetadataDTO(null, null, null, null, null, List.of()));
        if (content.getType() != ContentType.EPISODE) {
            when(contentPageStatsService.getStats(content.getId(), content.getType()))
                    .thenReturn(new ContentPageStatsDTO(content.getId(), null, 0, List.of(), 0, 0));
        }
        if (content.getType() != ContentType.SEASON) {
            when(contentViewerStateService.resolve(VIEWER_ID, List.of(coordinate), Map.of()))
                    .thenReturn(new ContentViewerStateService.Resolution(Map.of(coordinate, viewerState()), Map.of()));
        }
        when(diaryEntryRepository.countVisibleContentReviews(content.getId(), VIEWER_ID)).thenReturn(0L);
        if (content.getType() == ContentType.EPISODE) {
            when(contentPageStatsService.resolveStats(
                    eq(content.getId()), eq(content.getType()), org.mockito.ArgumentMatchers.anyCollection()))
                    .thenReturn(new ContentPageStatsService.Resolution(
                            new ContentPageStatsDTO(content.getId(), null, 0, List.of(), 0, 0), Map.of()));
        }
    }

    private Content content(
            ContentType type, UUID id, String tmdbId, String seriesTmdbId, Integer seasonNumber, Integer episodeNumber) {
        return Content.builder()
                .id(id)
                .tmdbId(tmdbId)
                .type(type)
                .seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber)
                .episodeNumber(episodeNumber)
                .build();
    }

    private User viewer() {
        return User.builder()
                .id(VIEWER_ID)
                .preferredLanguage("en-US")
                .preferredRegion("US")
                .build();
    }

    private ContentDetailsDTO details(UUID contentId, ContentType type) {
        return details(contentId, type, "/poster.jpg");
    }

    private ContentDetailsDTO details(UUID contentId, ContentType type, String posterPath) {
        return new ContentDetailsDTO(
                contentId, type, "Title", null, posterPath, null, null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null, List.of(), List.of(), List.of());
    }

    private ContentDetailsDTO detailsWithCrew(UUID contentId, ContentType type, String posterPath) {
        return new ContentDetailsDTO(
                contentId, type, "Title", null, posterPath, null, null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null, List.of(), List.of(new CrewMemberDTO(7, "Director", null, List.of("Director"))), List.of());
    }

    private ContentViewerStateDTO viewerState() {
        return viewerState(WatchStatus.UNWATCHED);
    }

    private ContentViewerStateDTO viewerState(WatchStatus watchStatus) {
        return new ContentViewerStateDTO(
                watchStatus, null, null, null, null, null, 0, null,
                false, null, false, null, List.of(), null, null);
    }

}
