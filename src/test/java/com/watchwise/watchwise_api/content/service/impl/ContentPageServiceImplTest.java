package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.content.dto.ContentDetailsDTO;
import com.watchwise.watchwise_api.content.dto.ContentNavigationDTO;
import com.watchwise.watchwise_api.content.dto.ContentPageDTO;
import com.watchwise.watchwise_api.content.dto.ContentPageMetadataDTO;
import com.watchwise.watchwise_api.content.dto.ContentPageSectionsDTO;
import com.watchwise.watchwise_api.content.dto.ContentPageStatsDTO;
import com.watchwise.watchwise_api.content.dto.ContentViewerStateDTO;
import com.watchwise.watchwise_api.content.dto.CrewMemberDTO;
import com.watchwise.watchwise_api.content.dto.WatchStatus;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.ContentDetailsService;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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
        Page<DiaryEntryRepository.ContentReviewKey> reviews = reviewPage(4);
        ContentCoordinate coordinate = ContentCoordinate.from(content);

        when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
        when(userRepository.findById(VIEWER_ID)).thenReturn(Optional.of(viewer));
        when(contentDetailsService.getDetails(contentId, VIEWER_ID)).thenReturn(details);
        when(contentPageMetadataService.getMetadata(content, viewer.getPreferredLanguage(), viewer.getPreferredRegion()))
                .thenReturn(metadata);
        when(contentPageStatsService.getStats(contentId)).thenReturn(stats);
        when(contentViewerStateService.resolve(VIEWER_ID, List.of(coordinate), Map.of()))
                .thenReturn(new ContentViewerStateService.Resolution(Map.of(coordinate, viewerState), Map.of()));
        when(diaryEntryRepository.findContentReviewKeys(contentId, VIEWER_ID, PageRequest.of(0, 1)))
                .thenReturn(reviews);
        when(contentChildCardAssembler.assembleSections(details, null, coordinate, VIEWER_ID)).thenReturn(sections);
        when(contentChildCardAssembler.assembleNavigation(details, null, coordinate, VIEWER_ID)).thenReturn(null);

        ContentPageDTO result = contentPageService.getPage(contentId, VIEWER_ID);

        assertThat(result).isEqualTo(new ContentPageDTO(
                details, metadata, stats, 4, viewerState, null, sections));
        verify(contentViewerStateService).resolve(VIEWER_ID, List.of(coordinate), Map.of());
        verify(contentChildCardAssembler).assembleSections(details, null, coordinate, VIEWER_ID);
        verify(contentChildCardAssembler).assembleNavigation(details, null, coordinate, VIEWER_ID);
        verifyNoMoreInteractions(contentPageMetadataService, contentPageStatsService, contentViewerStateService,
                contentChildCardAssembler);
    }

    @Test
    @DisplayName("[getPage] Should Assemble Series Sections Without Looking Up A Parent")
    void shouldAssembleSeriesSectionsWithoutLookingUpAParent() {
        UUID seriesId = UUID.randomUUID();
        Content series = content(ContentType.SERIES, seriesId, "1399", null, null, null);
        ContentDetailsDTO seriesDetails = details(seriesId, ContentType.SERIES);
        ContentPageSectionsDTO sections = new ContentPageSectionsDTO(List.of(), List.of(), List.of());
        ContentCoordinate coordinate = ContentCoordinate.from(series);
        stubCommonPageReads(series, seriesDetails, coordinate);
        when(contentChildCardAssembler.assembleSections(seriesDetails, null, coordinate, VIEWER_ID))
                .thenReturn(sections);
        when(contentChildCardAssembler.assembleNavigation(seriesDetails, null, coordinate, VIEWER_ID))
                .thenReturn(null);

        ContentPageDTO result = contentPageService.getPage(seriesId, VIEWER_ID);

        assertThat(result.sections()).isEqualTo(sections);
        verify(contentChildCardAssembler).assembleSections(seriesDetails, null, coordinate, VIEWER_ID);
        verify(contentRepository, org.mockito.Mockito.never())
                .findByTmdbIdAndType(org.mockito.ArgumentMatchers.anyString(), eq(ContentType.SERIES));
        verify(contentRepository, org.mockito.Mockito.never())
                .findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType(
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.isNull(), eq(ContentType.SEASON));
    }

    @Test
    @DisplayName("[getPage] Should Pass Parent Series Details To Season Cards")
    void shouldPassParentSeriesDetailsToSeasonCards() {
        UUID seasonId = UUID.randomUUID();
        UUID seriesId = UUID.randomUUID();
        Content season = content(ContentType.SEASON, seasonId, null, "1399", 2, null);
        Content series = content(ContentType.SERIES, seriesId, "1399", null, null, null);
        ContentDetailsDTO seasonDetails = details(seasonId, ContentType.SEASON);
        ContentDetailsDTO seriesDetails = details(seriesId, ContentType.SERIES);
        ContentPageSectionsDTO sections = new ContentPageSectionsDTO(List.of(), List.of(), List.of());
        ContentCoordinate coordinate = ContentCoordinate.from(season);
        stubCommonPageReads(season, seasonDetails, coordinate);
        when(contentRepository.findByTmdbIdAndType("1399", ContentType.SERIES)).thenReturn(Optional.of(series));
        when(contentDetailsService.getDetails(seriesId, VIEWER_ID)).thenReturn(seriesDetails);
        when(contentChildCardAssembler.assembleSections(
                seasonDetails, seriesDetails, coordinate, VIEWER_ID)).thenReturn(sections);
        when(contentChildCardAssembler.assembleNavigation(seasonDetails, null, coordinate, VIEWER_ID)).thenReturn(null);

        ContentPageDTO result = contentPageService.getPage(seasonId, VIEWER_ID);

        assertThat(result.sections()).isEqualTo(sections);
        verify(contentDetailsService).getDetails(seriesId, VIEWER_ID);
        verify(contentChildCardAssembler).assembleSections(seasonDetails, seriesDetails, coordinate, VIEWER_ID);
    }

    @Test
    @DisplayName("[getPage] Should Pass Parent Season Details To Episode Navigation")
    void shouldPassParentSeasonDetailsToEpisodeNavigation() {
        UUID episodeId = UUID.randomUUID();
        UUID seasonId = UUID.randomUUID();
        Content episode = content(ContentType.EPISODE, episodeId, null, "1399", 2, 3);
        Content season = content(ContentType.SEASON, seasonId, null, "1399", 2, null);
        ContentDetailsDTO episodeDetails = details(episodeId, ContentType.EPISODE);
        ContentDetailsDTO seasonDetails = details(seasonId, ContentType.SEASON);
        ContentPageSectionsDTO sections = new ContentPageSectionsDTO(List.of(), List.of(), List.of());
        ContentNavigationDTO navigation = new ContentNavigationDTO("1399", 2, 3, 8, null, null);
        ContentCoordinate coordinate = ContentCoordinate.from(episode);
        stubCommonPageReads(episode, episodeDetails, coordinate);
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType(
                "1399", 2, null, ContentType.SEASON)).thenReturn(Optional.of(season));
        when(contentDetailsService.getDetails(seasonId, VIEWER_ID)).thenReturn(seasonDetails);
        when(contentChildCardAssembler.assembleSections(episodeDetails, null, coordinate, VIEWER_ID))
                .thenReturn(sections);
        when(contentChildCardAssembler.assembleNavigation(episodeDetails, seasonDetails, coordinate, VIEWER_ID))
                .thenReturn(navigation);

        ContentPageDTO result = contentPageService.getPage(episodeId, VIEWER_ID);

        assertThat(result.navigation()).isEqualTo(navigation);
        verify(contentDetailsService).getDetails(seasonId, VIEWER_ID);
        verify(contentChildCardAssembler).assembleNavigation(episodeDetails, seasonDetails, coordinate, VIEWER_ID);
    }

    @Test
    @DisplayName("[getPage] Should Expose Episode Presentation Poster And Inherited Crew Flag")
    void shouldExposeEpisodePresentationPosterAndInheritedCrewFlag() {
        UUID episodeId = UUID.randomUUID();
        UUID seasonId = UUID.randomUUID();
        Content episode = content(ContentType.EPISODE, episodeId, null, "1399", 2, 3);
        Content season = content(ContentType.SEASON, seasonId, null, "1399", 2, null);
        ContentDetailsDTO episodeDetails = detailsWithCrew(episodeId, ContentType.EPISODE, "/episode-still.jpg");
        ContentDetailsDTO seasonDetails = details(seasonId, ContentType.SEASON, "/season-poster.jpg");
        ContentPageMetadataDTO metadata = new ContentPageMetadataDTO(null, null, null, null, null, List.of());
        ContentPageSectionsDTO sections = new ContentPageSectionsDTO(List.of(), List.of(), List.of());
        ContentCoordinate coordinate = ContentCoordinate.from(episode);
        stubCommonPageReads(episode, episodeDetails, coordinate);
        when(contentPageMetadataService.getMetadata(eq(episode), eq("en-US"), eq("US")))
                .thenReturn(metadata);
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType(
                "1399", 2, null, ContentType.SEASON)).thenReturn(Optional.of(season));
        when(contentDetailsService.getDetails(seasonId, VIEWER_ID)).thenReturn(seasonDetails);
        when(contentChildCardAssembler.assembleSections(episodeDetails, null, coordinate, VIEWER_ID))
                .thenReturn(sections);
        when(contentChildCardAssembler.assembleNavigation(episodeDetails, seasonDetails, coordinate, VIEWER_ID))
                .thenReturn(null);

        ContentPageDTO result = contentPageService.getPage(episodeId, VIEWER_ID);

        assertThat(result.metadata().presentationPosterPath()).isEqualTo("/season-poster.jpg");
        assertThat(result.metadata().presentationCrew()).isEqualTo(episodeDetails.crew());
        assertThat(result.metadata().crewInherited()).isTrue();
    }

    private void stubCommonPageReads(
            Content content, ContentDetailsDTO details, ContentCoordinate coordinate) {
        when(contentRepository.findById(content.getId())).thenReturn(Optional.of(content));
        when(userRepository.findById(VIEWER_ID)).thenReturn(Optional.of(viewer()));
        when(contentDetailsService.getDetails(content.getId(), VIEWER_ID)).thenReturn(details);
        lenient().when(contentPageMetadataService.getMetadata(eq(content), eq("en-US"), eq("US")))
                .thenReturn(new ContentPageMetadataDTO(null, null, null, null, null, List.of()));
        when(contentPageStatsService.getStats(content.getId()))
                .thenReturn(new ContentPageStatsDTO(content.getId(), null, 0, List.of(), 0, 0));
        when(contentViewerStateService.resolve(VIEWER_ID, List.of(coordinate), Map.of()))
                .thenReturn(new ContentViewerStateService.Resolution(Map.of(coordinate, viewerState()), Map.of()));
        Page<DiaryEntryRepository.ContentReviewKey> reviews = reviewPage(0);
        when(diaryEntryRepository.findContentReviewKeys(content.getId(), VIEWER_ID, PageRequest.of(0, 1)))
                .thenReturn(reviews);
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
        return new ContentViewerStateDTO(
                WatchStatus.UNWATCHED, null, null, null, null, null, 0, null,
                false, null, false, null, List.of(), null, null);
    }

    @SuppressWarnings("unchecked")
    private Page<DiaryEntryRepository.ContentReviewKey> reviewPage(long totalElements) {
        Page<DiaryEntryRepository.ContentReviewKey> page = mock(Page.class);
        when(page.getTotalElements()).thenReturn(totalElements);
        return page;
    }
}
