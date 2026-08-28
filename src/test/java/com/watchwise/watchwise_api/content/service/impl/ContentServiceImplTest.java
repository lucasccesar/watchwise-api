package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentServiceImplTest {

    @Mock
    private ContentMapper contentMapper;

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private NewTransactionExecutor newTransactionExecutor;

    @InjectMocks
    private ContentServiceImpl contentService;

    @BeforeEach
    void setUp() {
        lenient().when(newTransactionExecutor.runInNewTransaction(any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Null")
    void shouldThrowBadRequestExceptionWhenTypeIsNull() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, null, null, null, null, null, null);

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Type must be provided");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Movie And TmdbId Is Missing")
    void shouldThrowBadRequestExceptionWhenTypeIsMovieAndTmdbIdIsMissing() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.MOVIE, null, null, null, null, null);

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("tmdbId must be provided when type is MOVIE or SERIES");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Series And TmdbId Is Empty")
    void shouldThrowBadRequestExceptionWhenTypeIsSeriesAndTmdbIdIsEmpty() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("", ContentType.SERIES, null, null, null, null, null);

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("tmdbId must be provided when type is MOVIE or SERIES");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Series And TmdbId Is Whitespace Only")
    void shouldThrowBadRequestExceptionWhenTypeIsSeriesAndTmdbIdIsWhitespaceOnly() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("   ", ContentType.SERIES, null, null, null, null, null);

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("tmdbId must be provided when type is MOVIE or SERIES");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Movie And SeriesTmdbId Is Present")
    void shouldThrowBadRequestExceptionWhenTypeIsMovieAndSeriesTmdbIdIsPresent() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("100", ContentType.MOVIE, "200", null, null, null, null);

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("seriesTmdbId, seasonNumber and episodeNumber must not be provided when type is MOVIE or SERIES");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Season And SeriesTmdbId Is Missing")
    void shouldThrowBadRequestExceptionWhenTypeIsSeasonAndSeriesTmdbIdIsMissing() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.SEASON, null, 1, null, null, null);

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("seriesTmdbId and seasonNumber must be provided when type is SEASON");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Season And SeasonNumber Is Missing")
    void shouldThrowBadRequestExceptionWhenTypeIsSeasonAndSeasonNumberIsMissing() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.SEASON, "200", null, null, null, null);

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("seriesTmdbId and seasonNumber must be provided when type is SEASON");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Season And TmdbId Is Present")
    void shouldThrowBadRequestExceptionWhenTypeIsSeasonAndTmdbIdIsPresent() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("100", ContentType.SEASON, "200", 1, null, null, null);

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("tmdbId and episodeNumber must not be provided when type is SEASON");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Season And EpisodeNumber Is Present")
    void shouldThrowBadRequestExceptionWhenTypeIsSeasonAndEpisodeNumberIsPresent() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.SEASON, "200", 1, 3, null, null);

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("tmdbId and episodeNumber must not be provided when type is SEASON");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Episode And EpisodeNumber Is Missing")
    void shouldThrowBadRequestExceptionWhenTypeIsEpisodeAndEpisodeNumberIsMissing() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.EPISODE, "200", 1, null, null, null);

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("seriesTmdbId, seasonNumber and episodeNumber must be provided when type is EPISODE");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Episode And SeriesTmdbId Is Missing")
    void shouldThrowBadRequestExceptionWhenTypeIsEpisodeAndSeriesTmdbIdIsMissing() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.EPISODE, null, 1, 3, null, null);

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("seriesTmdbId, seasonNumber and episodeNumber must be provided when type is EPISODE");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Episode And TmdbId Is Present")
    void shouldThrowBadRequestExceptionWhenTypeIsEpisodeAndTmdbIdIsPresent() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("100", ContentType.EPISODE, "200", 1, 3, null, null);

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("tmdbId must not be provided when type is EPISODE");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Movie And IsSeasonFinale Is Present")
    void shouldThrowBadRequestExceptionWhenTypeIsMovieAndIsSeasonFinaleIsPresent() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("100", ContentType.MOVIE, null, null, null, true, null);

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("isSeasonFinale and isSeriesFinale must not be provided when type is MOVIE or SERIES");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Series And IsSeriesFinale Is Present")
    void shouldThrowBadRequestExceptionWhenTypeIsSeriesAndIsSeriesFinaleIsPresent() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("300", ContentType.SERIES, null, null, null, null, true);

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("isSeasonFinale and isSeriesFinale must not be provided when type is MOVIE or SERIES");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Season And IsSeasonFinale Is Present")
    void shouldThrowBadRequestExceptionWhenTypeIsSeasonAndIsSeasonFinaleIsPresent() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.SEASON, "200", 1, null, true, null);

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("isSeasonFinale must not be provided when type is SEASON");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Accept IsSeriesFinale - When Type Is Season")
    void shouldAcceptIsSeriesFinaleWhenTypeIsSeason() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.SEASON, "200", 1, null, null, true);
        Content mapped = Content.builder().seriesTmdbId("200").seasonNumber(1).type(ContentType.SEASON).isSeriesFinale(true).build();
        Content saved = Content.builder().id(UUID.randomUUID()).seriesTmdbId("200").seasonNumber(1).type(ContentType.SEASON).isSeriesFinale(true).build();
        ContentRefDTO responseDto = new ContentRefDTO(saved.getId(), null, ContentType.SEASON, "200", 1, null, null, true, null, null);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("200", 1, null, ContentType.SEASON))
                .thenReturn(Optional.empty());
        when(contentMapper.contentRefCreationDtoToContent(dto)).thenReturn(mapped);
        when(contentRepository.saveAndFlush(mapped)).thenReturn(saved);
        when(contentMapper.contentToContentRefDto(saved)).thenReturn(responseDto);

        ContentRefDTO result = contentService.getOrCreateReference(dto);

        assertThat(result).isEqualTo(responseDto);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Accept IsSeasonFinale And IsSeriesFinale - When Type Is Episode")
    void shouldAcceptIsSeasonFinaleAndIsSeriesFinaleWhenTypeIsEpisode() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.EPISODE, "200", 1, 3, true, true);
        Content mapped = Content.builder().seriesTmdbId("200").seasonNumber(1).episodeNumber(3).type(ContentType.EPISODE).isSeasonFinale(true).isSeriesFinale(true).build();
        Content saved = Content.builder().id(UUID.randomUUID()).seriesTmdbId("200").seasonNumber(1).episodeNumber(3).type(ContentType.EPISODE).isSeasonFinale(true).isSeriesFinale(true).build();
        ContentRefDTO responseDto = new ContentRefDTO(saved.getId(), null, ContentType.EPISODE, "200", 1, 3, true, true, null, null);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("200", 1, 3, ContentType.EPISODE))
                .thenReturn(Optional.empty());
        when(contentMapper.contentRefCreationDtoToContent(dto)).thenReturn(mapped);
        when(contentRepository.saveAndFlush(mapped)).thenReturn(saved);
        when(contentMapper.contentToContentRefDto(saved)).thenReturn(responseDto);

        ContentRefDTO result = contentService.getOrCreateReference(dto);

        assertThat(result).isEqualTo(responseDto);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Return Existing ContentRefDTO - When Movie Reference Already Exists")
    void shouldReturnExistingContentRefDtoWhenMovieReferenceAlreadyExists() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("100", ContentType.MOVIE, null, null, null, null, null);
        Content existing = Content.builder().id(UUID.randomUUID()).tmdbId("100").type(ContentType.MOVIE).build();
        ContentRefDTO responseDto = new ContentRefDTO(existing.getId(), "100", ContentType.MOVIE, null, null, null, null, null, null, null);

        when(contentRepository.findByTmdbIdAndType("100", ContentType.MOVIE)).thenReturn(Optional.of(existing));
        when(contentMapper.contentToContentRefDto(existing)).thenReturn(responseDto);

        ContentRefDTO result = contentService.getOrCreateReference(dto);

        assertThat(result).isEqualTo(responseDto);
        verify(contentRepository, never()).saveAndFlush(any());
        verify(contentMapper, never()).contentRefCreationDtoToContent(any());
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Create And Return ContentRefDTO - When Movie Reference Does Not Exist")
    void shouldCreateAndReturnContentRefDtoWhenMovieReferenceDoesNotExist() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("100", ContentType.MOVIE, null, null, null, null, null);
        Content mapped = Content.builder().tmdbId("100").type(ContentType.MOVIE).build();
        Content saved = Content.builder().id(UUID.randomUUID()).tmdbId("100").type(ContentType.MOVIE).build();
        ContentRefDTO responseDto = new ContentRefDTO(saved.getId(), "100", ContentType.MOVIE, null, null, null, null, null, null, null);

        when(contentRepository.findByTmdbIdAndType("100", ContentType.MOVIE)).thenReturn(Optional.empty());
        when(contentMapper.contentRefCreationDtoToContent(dto)).thenReturn(mapped);
        when(contentRepository.saveAndFlush(mapped)).thenReturn(saved);
        when(contentMapper.contentToContentRefDto(saved)).thenReturn(responseDto);

        ContentRefDTO result = contentService.getOrCreateReference(dto);

        assertThat(result).isEqualTo(responseDto);
        verify(contentRepository).saveAndFlush(mapped);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Attempt Save In A New Transaction - When Reference Does Not Exist")
    void shouldAttemptSaveInANewTransactionWhenReferenceDoesNotExist() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("100", ContentType.MOVIE, null, null, null, null, null);
        Content mapped = Content.builder().tmdbId("100").type(ContentType.MOVIE).build();
        Content saved = Content.builder().id(UUID.randomUUID()).tmdbId("100").type(ContentType.MOVIE).build();
        ContentRefDTO responseDto = new ContentRefDTO(saved.getId(), "100", ContentType.MOVIE, null, null, null, null, null, null, null);

        when(contentRepository.findByTmdbIdAndType("100", ContentType.MOVIE)).thenReturn(Optional.empty());
        when(contentMapper.contentRefCreationDtoToContent(dto)).thenReturn(mapped);
        when(contentRepository.saveAndFlush(mapped)).thenReturn(saved);
        when(contentMapper.contentToContentRefDto(saved)).thenReturn(responseDto);

        contentService.getOrCreateReference(dto);

        verify(newTransactionExecutor).runInNewTransaction(any());
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Trim TmdbId Before Querying And Persisting - When TmdbId Has Surrounding Whitespace")
    void shouldTrimTmdbIdBeforeQueryingAndPersistingWhenTmdbIdHasSurroundingWhitespace() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("  100  ", ContentType.MOVIE, null, null, null, null, null);
        ContentRefCreationDTO normalizedDto = new ContentRefCreationDTO("100", ContentType.MOVIE, null, null, null, null, null);
        Content mapped = Content.builder().tmdbId("100").type(ContentType.MOVIE).build();
        Content saved = Content.builder().id(UUID.randomUUID()).tmdbId("100").type(ContentType.MOVIE).build();
        ContentRefDTO responseDto = new ContentRefDTO(saved.getId(), "100", ContentType.MOVIE, null, null, null, null, null, null, null);

        when(contentRepository.findByTmdbIdAndType("100", ContentType.MOVIE)).thenReturn(Optional.empty());
        when(contentMapper.contentRefCreationDtoToContent(normalizedDto)).thenReturn(mapped);
        when(contentRepository.saveAndFlush(mapped)).thenReturn(saved);
        when(contentMapper.contentToContentRefDto(saved)).thenReturn(responseDto);

        ContentRefDTO result = contentService.getOrCreateReference(dto);

        assertThat(result).isEqualTo(responseDto);
        verify(contentRepository).findByTmdbIdAndType("100", ContentType.MOVIE);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Trim SeriesTmdbId Before Querying And Persisting - When SeriesTmdbId Has Surrounding Whitespace")
    void shouldTrimSeriesTmdbIdBeforeQueryingAndPersistingWhenSeriesTmdbIdHasSurroundingWhitespace() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.SEASON, "  200  ", 1, null, null, null);
        ContentRefCreationDTO normalizedDto = new ContentRefCreationDTO(null, ContentType.SEASON, "200", 1, null, null, null);
        Content mapped = Content.builder().seriesTmdbId("200").seasonNumber(1).type(ContentType.SEASON).build();
        Content saved = Content.builder().id(UUID.randomUUID()).seriesTmdbId("200").seasonNumber(1).type(ContentType.SEASON).build();
        ContentRefDTO responseDto = new ContentRefDTO(saved.getId(), null, ContentType.SEASON, "200", 1, null, null, null, null, null);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("200", 1, null, ContentType.SEASON))
                .thenReturn(Optional.empty());
        when(contentMapper.contentRefCreationDtoToContent(normalizedDto)).thenReturn(mapped);
        when(contentRepository.saveAndFlush(mapped)).thenReturn(saved);
        when(contentMapper.contentToContentRefDto(saved)).thenReturn(responseDto);

        ContentRefDTO result = contentService.getOrCreateReference(dto);

        assertThat(result).isEqualTo(responseDto);
        verify(contentRepository)
                .findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("200", 1, null, ContentType.SEASON);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Set CreatedAt And UpdatedAt - When Creating New Reference")
    void shouldSetCreatedAtAndUpdatedAtWhenCreatingNewReference() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("100", ContentType.MOVIE, null, null, null, null, null);
        Content mapped = Content.builder().tmdbId("100").type(ContentType.MOVIE).build();

        when(contentRepository.findByTmdbIdAndType("100", ContentType.MOVIE)).thenReturn(Optional.empty());
        when(contentMapper.contentRefCreationDtoToContent(dto)).thenReturn(mapped);
        when(contentRepository.saveAndFlush(mapped)).thenReturn(mapped);
        when(contentMapper.contentToContentRefDto(mapped)).thenReturn(
                new ContentRefDTO(null, "100", ContentType.MOVIE, null, null, null, null, null, null, null));

        ArgumentCaptor<Content> captor = ArgumentCaptor.forClass(Content.class);

        contentService.getOrCreateReference(dto);

        verify(contentRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getCreatedAt()).isNotNull();
        assertThat(captor.getValue().getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Return Existing ContentRefDTO - When Series Reference Already Exists")
    void shouldReturnExistingContentRefDtoWhenSeriesReferenceAlreadyExists() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("300", ContentType.SERIES, null, null, null, null, null);
        Content existing = Content.builder().id(UUID.randomUUID()).tmdbId("300").type(ContentType.SERIES).build();
        ContentRefDTO responseDto = new ContentRefDTO(existing.getId(), "300", ContentType.SERIES, null, null, null, null, null, null, null);

        when(contentRepository.findByTmdbIdAndType("300", ContentType.SERIES)).thenReturn(Optional.of(existing));
        when(contentMapper.contentToContentRefDto(existing)).thenReturn(responseDto);

        ContentRefDTO result = contentService.getOrCreateReference(dto);

        assertThat(result).isEqualTo(responseDto);
        verify(contentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Return Existing ContentRefDTO - When Season Reference Already Exists")
    void shouldReturnExistingContentRefDtoWhenSeasonReferenceAlreadyExists() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.SEASON, "200", 1, null, null, null);
        Content existing = Content.builder().id(UUID.randomUUID()).seriesTmdbId("200").seasonNumber(1).type(ContentType.SEASON).build();
        ContentRefDTO responseDto = new ContentRefDTO(existing.getId(), null, ContentType.SEASON, "200", 1, null, null, null, null, null);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("200", 1, null, ContentType.SEASON))
                .thenReturn(Optional.of(existing));
        when(contentMapper.contentToContentRefDto(existing)).thenReturn(responseDto);

        ContentRefDTO result = contentService.getOrCreateReference(dto);

        assertThat(result).isEqualTo(responseDto);
        verify(contentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Query With Null EpisodeNumber - When Type Is Season")
    void shouldQueryWithNullEpisodeNumberWhenTypeIsSeason() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.SEASON, "200", 1, null, null, null);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType(
                eq("200"), eq(1), isNull(), eq(ContentType.SEASON)))
                .thenReturn(Optional.empty());
        when(contentMapper.contentRefCreationDtoToContent(dto)).thenReturn(Content.builder().build());
        when(contentRepository.saveAndFlush(any())).thenReturn(Content.builder().build());
        when(contentMapper.contentToContentRefDto(any())).thenReturn(
                new ContentRefDTO(null, null, ContentType.SEASON, "200", 1, null, null, null, null, null));

        contentService.getOrCreateReference(dto);

        verify(contentRepository).findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType(
                eq("200"), eq(1), isNull(), eq(ContentType.SEASON));
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Create And Return ContentRefDTO - When Season Reference Does Not Exist")
    void shouldCreateAndReturnContentRefDtoWhenSeasonReferenceDoesNotExist() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.SEASON, "200", 1, null, null, null);
        Content mapped = Content.builder().seriesTmdbId("200").seasonNumber(1).type(ContentType.SEASON).build();
        Content saved = Content.builder().id(UUID.randomUUID()).seriesTmdbId("200").seasonNumber(1).type(ContentType.SEASON).build();
        ContentRefDTO responseDto = new ContentRefDTO(saved.getId(), null, ContentType.SEASON, "200", 1, null, null, null, null, null);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("200", 1, null, ContentType.SEASON))
                .thenReturn(Optional.empty());
        when(contentMapper.contentRefCreationDtoToContent(dto)).thenReturn(mapped);
        when(contentRepository.saveAndFlush(mapped)).thenReturn(saved);
        when(contentMapper.contentToContentRefDto(saved)).thenReturn(responseDto);

        ContentRefDTO result = contentService.getOrCreateReference(dto);

        assertThat(result).isEqualTo(responseDto);
        verify(contentRepository).saveAndFlush(mapped);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Clear Previous Series Finale - When Creating A New Season Marked As The Series Finale")
    void shouldClearPreviousSeriesFinaleWhenCreatingANewSeasonMarkedAsTheSeriesFinale() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.SEASON, "200", 6, null, null, true);
        Content previousFinale = Content.builder().id(UUID.randomUUID()).seriesTmdbId("200").seasonNumber(5).type(ContentType.SEASON).isSeriesFinale(true).build();
        Content mapped = Content.builder().seriesTmdbId("200").seasonNumber(6).type(ContentType.SEASON).isSeriesFinale(true).build();
        Content saved = Content.builder().id(UUID.randomUUID()).seriesTmdbId("200").seasonNumber(6).type(ContentType.SEASON).isSeriesFinale(true).build();
        ContentRefDTO responseDto = new ContentRefDTO(saved.getId(), null, ContentType.SEASON, "200", 6, null, null, true, null, null);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("200", 6, null, ContentType.SEASON))
                .thenReturn(Optional.empty());
        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("200", ContentType.SEASON))
                .thenReturn(Optional.of(previousFinale));
        when(contentRepository.saveAndFlush(previousFinale)).thenReturn(previousFinale);
        when(contentMapper.contentRefCreationDtoToContent(dto)).thenReturn(mapped);
        when(contentRepository.saveAndFlush(mapped)).thenReturn(saved);
        when(contentMapper.contentToContentRefDto(saved)).thenReturn(responseDto);

        ContentRefDTO result = contentService.getOrCreateReference(dto);

        assertThat(result).isEqualTo(responseDto);
        assertThat(previousFinale.getIsSeriesFinale()).isFalse();
        assertThat(previousFinale.getUpdatedAt()).isNotNull();
        verify(contentRepository).saveAndFlush(previousFinale);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Defer Clearing The Previous Series Finale Until The Same REQUIRES_NEW Transaction As The New Content - When Creating A New Season Marked As The Series Finale")
    void shouldDeferClearingThePreviousSeriesFinaleUntilTheSameTransactionAsTheNewContentWhenCreatingANewSeasonMarkedAsTheSeriesFinale() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.SEASON, "200", 6, null, null, true);
        Content previousFinale = Content.builder().id(UUID.randomUUID()).seriesTmdbId("200").seasonNumber(5).type(ContentType.SEASON).isSeriesFinale(true).build();
        Content mapped = Content.builder().seriesTmdbId("200").seasonNumber(6).type(ContentType.SEASON).isSeriesFinale(true).build();
        Content saved = Content.builder().id(UUID.randomUUID()).seriesTmdbId("200").seasonNumber(6).type(ContentType.SEASON).isSeriesFinale(true).build();

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("200", 6, null, ContentType.SEASON))
                .thenReturn(Optional.empty());
        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("200", ContentType.SEASON))
                .thenReturn(Optional.of(previousFinale));
        when(contentRepository.saveAndFlush(previousFinale)).thenReturn(previousFinale);
        when(contentMapper.contentRefCreationDtoToContent(dto)).thenReturn(mapped);
        when(contentRepository.saveAndFlush(mapped)).thenReturn(saved);

        ArgumentCaptor<Supplier<Content>> supplierCaptor = ArgumentCaptor.forClass(Supplier.class);
        doReturn(saved).when(newTransactionExecutor).runInNewTransaction(supplierCaptor.capture());

        contentService.getOrCreateReference(dto);

        assertThat(previousFinale.getIsSeriesFinale()).isTrue();
        verify(contentRepository, never()).saveAndFlush(previousFinale);

        supplierCaptor.getValue().get();

        assertThat(previousFinale.getIsSeriesFinale()).isFalse();
        verify(contentRepository).saveAndFlush(previousFinale);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Not Clear Previous Series Finale - When New Season Is Earlier Than The Current Finale")
    void shouldNotClearPreviousSeriesFinaleWhenNewSeasonIsEarlierThanTheCurrentFinale() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.SEASON, "200", 3, null, null, true);
        Content laterFinale = Content.builder().id(UUID.randomUUID()).seriesTmdbId("200").seasonNumber(6).type(ContentType.SEASON).isSeriesFinale(true).build();
        Content mapped = Content.builder().seriesTmdbId("200").seasonNumber(3).type(ContentType.SEASON).isSeriesFinale(true).build();

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("200", 3, null, ContentType.SEASON))
                .thenReturn(Optional.empty());
        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("200", ContentType.SEASON))
                .thenReturn(Optional.of(laterFinale));
        when(contentMapper.contentRefCreationDtoToContent(dto)).thenReturn(mapped);
        when(contentRepository.saveAndFlush(mapped))
                .thenThrow(buildDataIntegrityViolationException("uq_contents_series_finale"));

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Another season is already marked as this series' finale");

        verify(contentRepository, never()).saveAndFlush(laterFinale);
        assertThat(laterFinale.getIsSeriesFinale()).isTrue();
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Not Look Up Previous Series Finale - When New Season Is Not Marked As The Series Finale")
    void shouldNotLookUpPreviousSeriesFinaleWhenNewSeasonIsNotMarkedAsTheSeriesFinale() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.SEASON, "200", 6, null, null, null);
        Content mapped = Content.builder().seriesTmdbId("200").seasonNumber(6).type(ContentType.SEASON).build();
        Content saved = Content.builder().id(UUID.randomUUID()).seriesTmdbId("200").seasonNumber(6).type(ContentType.SEASON).build();
        ContentRefDTO responseDto = new ContentRefDTO(saved.getId(), null, ContentType.SEASON, "200", 6, null, null, null, null, null);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("200", 6, null, ContentType.SEASON))
                .thenReturn(Optional.empty());
        when(contentMapper.contentRefCreationDtoToContent(dto)).thenReturn(mapped);
        when(contentRepository.saveAndFlush(mapped)).thenReturn(saved);
        when(contentMapper.contentToContentRefDto(saved)).thenReturn(responseDto);

        contentService.getOrCreateReference(dto);

        verify(contentRepository, never()).findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue(any(), any());
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Clear Previous Season Finale - When Creating A New Episode Marked As The Season Finale")
    void shouldClearPreviousSeasonFinaleWhenCreatingANewEpisodeMarkedAsTheSeasonFinale() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.EPISODE, "200", 1, 11, true, null);
        Content previousFinale = Content.builder().id(UUID.randomUUID()).seriesTmdbId("200").seasonNumber(1).episodeNumber(10).type(ContentType.EPISODE).isSeasonFinale(true).build();
        Content mapped = Content.builder().seriesTmdbId("200").seasonNumber(1).episodeNumber(11).type(ContentType.EPISODE).isSeasonFinale(true).build();
        Content saved = Content.builder().id(UUID.randomUUID()).seriesTmdbId("200").seasonNumber(1).episodeNumber(11).type(ContentType.EPISODE).isSeasonFinale(true).build();
        ContentRefDTO responseDto = new ContentRefDTO(saved.getId(), null, ContentType.EPISODE, "200", 1, 11, true, null, null, null);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("200", 1, 11, ContentType.EPISODE))
                .thenReturn(Optional.empty());
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("200", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(previousFinale));
        when(contentRepository.saveAndFlush(previousFinale)).thenReturn(previousFinale);
        when(contentMapper.contentRefCreationDtoToContent(dto)).thenReturn(mapped);
        when(contentRepository.saveAndFlush(mapped)).thenReturn(saved);
        when(contentMapper.contentToContentRefDto(saved)).thenReturn(responseDto);

        ContentRefDTO result = contentService.getOrCreateReference(dto);

        assertThat(result).isEqualTo(responseDto);
        assertThat(previousFinale.getIsSeasonFinale()).isFalse();
        assertThat(previousFinale.getUpdatedAt()).isNotNull();
        verify(contentRepository).saveAndFlush(previousFinale);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Also Clear Previous Series Finale - When The Superseded Season Finale Episode Was Also The Series Finale")
    void shouldAlsoClearPreviousSeriesFinaleWhenTheSupersededSeasonFinaleEpisodeWasAlsoTheSeriesFinale() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.EPISODE, "200", 1, 11, true, null);
        Content previousFinale = Content.builder().id(UUID.randomUUID()).seriesTmdbId("200").seasonNumber(1).episodeNumber(10).type(ContentType.EPISODE).isSeasonFinale(true).isSeriesFinale(true).build();
        Content mapped = Content.builder().seriesTmdbId("200").seasonNumber(1).episodeNumber(11).type(ContentType.EPISODE).isSeasonFinale(true).build();
        Content saved = Content.builder().id(UUID.randomUUID()).seriesTmdbId("200").seasonNumber(1).episodeNumber(11).type(ContentType.EPISODE).isSeasonFinale(true).build();
        ContentRefDTO responseDto = new ContentRefDTO(saved.getId(), null, ContentType.EPISODE, "200", 1, 11, true, null, null, null);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("200", 1, 11, ContentType.EPISODE))
                .thenReturn(Optional.empty());
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("200", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(previousFinale));
        when(contentRepository.saveAndFlush(previousFinale)).thenReturn(previousFinale);
        when(contentMapper.contentRefCreationDtoToContent(dto)).thenReturn(mapped);
        when(contentRepository.saveAndFlush(mapped)).thenReturn(saved);
        when(contentMapper.contentToContentRefDto(saved)).thenReturn(responseDto);

        contentService.getOrCreateReference(dto);

        assertThat(previousFinale.getIsSeasonFinale()).isFalse();
        assertThat(previousFinale.getIsSeriesFinale()).isFalse();
        verify(contentRepository).saveAndFlush(previousFinale);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Not Clear Previous Season Finale - When New Episode Is Earlier Than The Current Finale")
    void shouldNotClearPreviousSeasonFinaleWhenNewEpisodeIsEarlierThanTheCurrentFinale() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.EPISODE, "200", 1, 5, true, null);
        Content laterFinale = Content.builder().id(UUID.randomUUID()).seriesTmdbId("200").seasonNumber(1).episodeNumber(10).type(ContentType.EPISODE).isSeasonFinale(true).build();
        Content mapped = Content.builder().seriesTmdbId("200").seasonNumber(1).episodeNumber(5).type(ContentType.EPISODE).isSeasonFinale(true).build();

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("200", 1, 5, ContentType.EPISODE))
                .thenReturn(Optional.empty());
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("200", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(laterFinale));
        when(contentMapper.contentRefCreationDtoToContent(dto)).thenReturn(mapped);
        when(contentRepository.saveAndFlush(mapped))
                .thenThrow(buildDataIntegrityViolationException("uq_contents_season_finale"));

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Another episode is already marked as this season's finale");

        verify(contentRepository, never()).saveAndFlush(laterFinale);
        assertThat(laterFinale.getIsSeasonFinale()).isTrue();
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Not Look Up Previous Season Finale - When New Episode Is Not Marked As The Season Finale")
    void shouldNotLookUpPreviousSeasonFinaleWhenNewEpisodeIsNotMarkedAsTheSeasonFinale() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.EPISODE, "200", 1, 5, null, null);
        Content mapped = Content.builder().seriesTmdbId("200").seasonNumber(1).episodeNumber(5).type(ContentType.EPISODE).build();
        Content saved = Content.builder().id(UUID.randomUUID()).seriesTmdbId("200").seasonNumber(1).episodeNumber(5).type(ContentType.EPISODE).build();
        ContentRefDTO responseDto = new ContentRefDTO(saved.getId(), null, ContentType.EPISODE, "200", 1, 5, null, null, null, null);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("200", 1, 5, ContentType.EPISODE))
                .thenReturn(Optional.empty());
        when(contentMapper.contentRefCreationDtoToContent(dto)).thenReturn(mapped);
        when(contentRepository.saveAndFlush(mapped)).thenReturn(saved);
        when(contentMapper.contentToContentRefDto(saved)).thenReturn(responseDto);

        contentService.getOrCreateReference(dto);

        verify(contentRepository, never()).findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue(any(), any(), any());
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Return Existing ContentRefDTO - When Episode Reference Already Exists")
    void shouldReturnExistingContentRefDtoWhenEpisodeReferenceAlreadyExists() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.EPISODE, "200", 1, 3, null, null);
        Content existing = Content.builder().id(UUID.randomUUID()).seriesTmdbId("200").seasonNumber(1).episodeNumber(3).type(ContentType.EPISODE).build();
        ContentRefDTO responseDto = new ContentRefDTO(existing.getId(), null, ContentType.EPISODE, "200", 1, 3, null, null, null, null);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("200", 1, 3, ContentType.EPISODE))
                .thenReturn(Optional.of(existing));
        when(contentMapper.contentToContentRefDto(existing)).thenReturn(responseDto);

        ContentRefDTO result = contentService.getOrCreateReference(dto);

        assertThat(result).isEqualTo(responseDto);
        verify(contentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw ConflictException - When Existing Episode Has A Different IsSeasonFinale Value")
    void shouldThrowConflictExceptionWhenExistingEpisodeHasADifferentIsSeasonFinaleValue() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.EPISODE, "200", 1, 3, true, null);
        Content existing = Content.builder().id(UUID.randomUUID()).seriesTmdbId("200").seasonNumber(1).episodeNumber(3).type(ContentType.EPISODE).isSeasonFinale(false).build();

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("200", 1, 3, ContentType.EPISODE))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(ConflictException.class)
                .hasMessage("This content is already registered with a different isSeasonFinale value");

        verify(contentMapper, never()).contentToContentRefDto(any());
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw ConflictException - When Existing Season Has A Different IsSeriesFinale Value")
    void shouldThrowConflictExceptionWhenExistingSeasonHasADifferentIsSeriesFinaleValue() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.SEASON, "200", 4, null, null, false);
        Content existing = Content.builder().id(UUID.randomUUID()).seriesTmdbId("200").seasonNumber(4).type(ContentType.SEASON).isSeriesFinale(true).build();

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("200", 4, null, ContentType.SEASON))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(ConflictException.class)
                .hasMessage("This content is already registered with a different isSeriesFinale value");

        verify(contentMapper, never()).contentToContentRefDto(any());
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Return Existing ContentRefDTO - When Requested Finale Flag Is Null Even Though The Existing Value Differs")
    void shouldReturnExistingContentRefDtoWhenRequestedFinaleFlagIsNullEvenThoughTheExistingValueDiffers() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.EPISODE, "200", 1, 3, null, null);
        Content existing = Content.builder().id(UUID.randomUUID()).seriesTmdbId("200").seasonNumber(1).episodeNumber(3).type(ContentType.EPISODE).isSeasonFinale(true).build();
        ContentRefDTO responseDto = new ContentRefDTO(existing.getId(), null, ContentType.EPISODE, "200", 1, 3, true, null, null, null);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("200", 1, 3, ContentType.EPISODE))
                .thenReturn(Optional.of(existing));
        when(contentMapper.contentToContentRefDto(existing)).thenReturn(responseDto);

        ContentRefDTO result = contentService.getOrCreateReference(dto);

        assertThat(result).isEqualTo(responseDto);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw ConflictException - When Concurrent Creation Recovery Finds A Different IsSeasonFinale Value")
    void shouldThrowConflictExceptionWhenConcurrentCreationRecoveryFindsADifferentIsSeasonFinaleValue() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.EPISODE, "200", 1, 3, true, null);
        Content mapped = Content.builder().seriesTmdbId("200").seasonNumber(1).episodeNumber(3).type(ContentType.EPISODE).isSeasonFinale(true).build();
        Content existing = Content.builder().id(UUID.randomUUID()).seriesTmdbId("200").seasonNumber(1).episodeNumber(3).type(ContentType.EPISODE).isSeasonFinale(false).build();

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("200", 1, 3, ContentType.EPISODE))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(contentMapper.contentRefCreationDtoToContent(dto)).thenReturn(mapped);
        when(contentRepository.saveAndFlush(mapped))
                .thenThrow(buildDataIntegrityViolationException("uq_contents_tmdb_id_type"));

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(ConflictException.class)
                .hasMessage("This content is already registered with a different isSeasonFinale value");
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Create And Return ContentRefDTO - When Episode Reference Does Not Exist")
    void shouldCreateAndReturnContentRefDtoWhenEpisodeReferenceDoesNotExist() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.EPISODE, "200", 1, 3, null, null);
        Content mapped = Content.builder().seriesTmdbId("200").seasonNumber(1).episodeNumber(3).type(ContentType.EPISODE).build();
        Content saved = Content.builder().id(UUID.randomUUID()).seriesTmdbId("200").seasonNumber(1).episodeNumber(3).type(ContentType.EPISODE).build();
        ContentRefDTO responseDto = new ContentRefDTO(saved.getId(), null, ContentType.EPISODE, "200", 1, 3, null, null, null, null);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("200", 1, 3, ContentType.EPISODE))
                .thenReturn(Optional.empty());
        when(contentMapper.contentRefCreationDtoToContent(dto)).thenReturn(mapped);
        when(contentRepository.saveAndFlush(mapped)).thenReturn(saved);
        when(contentMapper.contentToContentRefDto(saved)).thenReturn(responseDto);

        ContentRefDTO result = contentService.getOrCreateReference(dto);

        assertThat(result).isEqualTo(responseDto);
        verify(contentRepository).saveAndFlush(mapped);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Return Existing ContentRefDTO - When Save Throws DataIntegrityViolationException Due To Concurrent Creation")
    void shouldReturnExistingContentRefDtoWhenSaveThrowsDataIntegrityViolationExceptionDueToConcurrentCreation() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("100", ContentType.MOVIE, null, null, null, null, null);
        Content mapped = Content.builder().tmdbId("100").type(ContentType.MOVIE).build();
        Content existing = Content.builder().id(UUID.randomUUID()).tmdbId("100").type(ContentType.MOVIE).build();
        ContentRefDTO responseDto = new ContentRefDTO(existing.getId(), "100", ContentType.MOVIE, null, null, null, null, null, null, null);

        when(contentRepository.findByTmdbIdAndType("100", ContentType.MOVIE))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(contentMapper.contentRefCreationDtoToContent(dto)).thenReturn(mapped);
        when(contentRepository.saveAndFlush(mapped))
                .thenThrow(buildDataIntegrityViolationException("uq_contents_tmdb_id_type"));
        when(contentMapper.contentToContentRefDto(existing)).thenReturn(responseDto);

        ContentRefDTO result = contentService.getOrCreateReference(dto);

        assertThat(result).isEqualTo(responseDto);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Return Existing ContentRefDTO - When Save Fails With Season Finale Constraint But The Same Episode Already Exists")
    void shouldReturnExistingContentRefDtoWhenSaveFailsWithSeasonFinaleConstraintButTheSameEpisodeAlreadyExists() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.EPISODE, "200", 1, 5, true, null);
        Content mapped = Content.builder().seriesTmdbId("200").seasonNumber(1).episodeNumber(5).type(ContentType.EPISODE).isSeasonFinale(true).build();
        Content existing = Content.builder().id(UUID.randomUUID()).seriesTmdbId("200").seasonNumber(1).episodeNumber(5).type(ContentType.EPISODE).isSeasonFinale(true).build();
        ContentRefDTO responseDto = new ContentRefDTO(existing.getId(), null, ContentType.EPISODE, "200", 1, 5, true, null, null, null);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("200", 1, 5, ContentType.EPISODE))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(contentMapper.contentRefCreationDtoToContent(dto)).thenReturn(mapped);
        when(contentRepository.saveAndFlush(mapped))
                .thenThrow(buildDataIntegrityViolationException("uq_contents_season_finale"));
        when(contentMapper.contentToContentRefDto(existing)).thenReturn(responseDto);

        ContentRefDTO result = contentService.getOrCreateReference(dto);

        assertThat(result).isEqualTo(responseDto);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw ConflictException - When Another Episode Is Already Marked As The Season Finale")
    void shouldThrowConflictExceptionWhenAnotherEpisodeIsAlreadyMarkedAsTheSeasonFinale() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.EPISODE, "200", 1, 5, true, null);
        Content mapped = Content.builder().seriesTmdbId("200").seasonNumber(1).episodeNumber(5).type(ContentType.EPISODE).isSeasonFinale(true).build();

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("200", 1, 5, ContentType.EPISODE))
                .thenReturn(Optional.empty());
        when(contentMapper.contentRefCreationDtoToContent(dto)).thenReturn(mapped);
        when(contentRepository.saveAndFlush(mapped))
                .thenThrow(buildDataIntegrityViolationException("uq_contents_season_finale"));

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Another episode is already marked as this season's finale");

        verify(contentMapper, never()).contentToContentRefDto(any());
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw ConflictException - When Another Season Is Already Marked As The Series Finale")
    void shouldThrowConflictExceptionWhenAnotherSeasonIsAlreadyMarkedAsTheSeriesFinale() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.SEASON, "200", 4, null, null, true);
        Content mapped = Content.builder().seriesTmdbId("200").seasonNumber(4).type(ContentType.SEASON).isSeriesFinale(true).build();

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("200", 4, null, ContentType.SEASON))
                .thenReturn(Optional.empty());
        when(contentMapper.contentRefCreationDtoToContent(dto)).thenReturn(mapped);
        when(contentRepository.saveAndFlush(mapped))
                .thenThrow(buildDataIntegrityViolationException("uq_contents_series_finale"));

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Another season is already marked as this series' finale");

        verify(contentMapper, never()).contentToContentRefDto(any());
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Rethrow DataIntegrityViolationException - When Save Fails And Reference Is Still Not Found")
    void shouldRethrowDataIntegrityViolationExceptionWhenSaveFailsAndReferenceIsStillNotFound() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("100", ContentType.MOVIE, null, null, null, null, null);
        Content mapped = Content.builder().tmdbId("100").type(ContentType.MOVIE).build();
        DataIntegrityViolationException exception = buildDataIntegrityViolationException("uq_contents_tmdb_id_type");

        when(contentRepository.findByTmdbIdAndType("100", ContentType.MOVIE)).thenReturn(Optional.empty());
        when(contentMapper.contentRefCreationDtoToContent(dto)).thenReturn(mapped);
        when(contentRepository.saveAndFlush(mapped)).thenThrow(exception);

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isSameAs(exception);

        verify(contentMapper, never()).contentToContentRefDto(any());
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Accept RuntimeMinutes - When Type Is Movie")
    void shouldAcceptRuntimeMinutesWhenTypeIsMovie() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("100", ContentType.MOVIE, null, null, null, null, null, 139, null);
        Content mapped = Content.builder().tmdbId("100").type(ContentType.MOVIE).runtimeMinutes(139).build();
        Content saved = Content.builder().id(UUID.randomUUID()).tmdbId("100").type(ContentType.MOVIE).runtimeMinutes(139).build();
        ContentRefDTO responseDto = new ContentRefDTO(saved.getId(), "100", ContentType.MOVIE, null, null, null, null, null, null, null, 139, null);

        when(contentRepository.findByTmdbIdAndType("100", ContentType.MOVIE)).thenReturn(Optional.empty());
        when(contentMapper.contentRefCreationDtoToContent(dto)).thenReturn(mapped);
        when(contentRepository.saveAndFlush(mapped)).thenReturn(saved);
        when(contentMapper.contentToContentRefDto(saved)).thenReturn(responseDto);

        ContentRefDTO result = contentService.getOrCreateReference(dto);

        assertThat(result).isEqualTo(responseDto);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Series And RuntimeMinutes Is Present")
    void shouldThrowBadRequestExceptionWhenTypeIsSeriesAndRuntimeMinutesIsPresent() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("300", ContentType.SERIES, null, null, null, null, null, 45, null);

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("runtimeMinutes must not be provided when type is SERIES");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Season And RuntimeMinutes Is Present")
    void shouldThrowBadRequestExceptionWhenTypeIsSeasonAndRuntimeMinutesIsPresent() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.SEASON, "200", 1, null, null, null, 45, null);

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("runtimeMinutes must not be provided when type is SEASON");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw ConflictException - When Existing Content Has A Different RuntimeMinutes Value")
    void shouldThrowConflictExceptionWhenExistingContentHasADifferentRuntimeMinutesValue() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("100", ContentType.MOVIE, null, null, null, null, null, 139, null);
        Content existing = Content.builder().id(UUID.randomUUID()).tmdbId("100").type(ContentType.MOVIE).runtimeMinutes(120).build();

        when(contentRepository.findByTmdbIdAndType("100", ContentType.MOVIE)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(ConflictException.class)
                .hasMessage("This content is already registered with a different runtimeMinutes value");

        verify(contentMapper, never()).contentToContentRefDto(any());
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Accept Genres - When Type Is Series")
    void shouldAcceptGenresWhenTypeIsSeries() {
        List<String> genres = List.of("Drama", "Thriller");
        ContentRefCreationDTO dto = new ContentRefCreationDTO("300", ContentType.SERIES, null, null, null, null, null, null, genres);
        Content mapped = Content.builder().tmdbId("300").type(ContentType.SERIES).genres(genres).build();
        Content saved = Content.builder().id(UUID.randomUUID()).tmdbId("300").type(ContentType.SERIES).genres(genres).build();
        ContentRefDTO responseDto = new ContentRefDTO(saved.getId(), "300", ContentType.SERIES, null, null, null, null, null, null, null, null, genres);

        when(contentRepository.findByTmdbIdAndType("300", ContentType.SERIES)).thenReturn(Optional.empty());
        when(contentMapper.contentRefCreationDtoToContent(dto)).thenReturn(mapped);
        when(contentRepository.saveAndFlush(mapped)).thenReturn(saved);
        when(contentMapper.contentToContentRefDto(saved)).thenReturn(responseDto);

        ContentRefDTO result = contentService.getOrCreateReference(dto);

        assertThat(result).isEqualTo(responseDto);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Season And Genres Is Present")
    void shouldThrowBadRequestExceptionWhenTypeIsSeasonAndGenresIsPresent() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.SEASON, "200", 1, null, null, null, null, List.of("Drama"));

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("genres must not be provided when type is SEASON");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Episode And Genres Is Present")
    void shouldThrowBadRequestExceptionWhenTypeIsEpisodeAndGenresIsPresent() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.EPISODE, "200", 1, 3, null, null, null, List.of("Drama"));

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("genres must not be provided when type is EPISODE");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw ConflictException - When Existing Content Has A Different Genres Value")
    void shouldThrowConflictExceptionWhenExistingContentHasADifferentGenresValue() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("300", ContentType.SERIES, null, null, null, null, null, null, List.of("Drama"));
        Content existing = Content.builder().id(UUID.randomUUID()).tmdbId("300").type(ContentType.SERIES).genres(List.of("Comedy")).build();

        when(contentRepository.findByTmdbIdAndType("300", ContentType.SERIES)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(ConflictException.class)
                .hasMessage("This content is already registered with a different genres value");

        verify(contentMapper, never()).contentToContentRefDto(any());
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Season And ReleaseYear Is Present")
    void shouldThrowBadRequestExceptionWhenTypeIsSeasonAndReleaseYearIsPresent() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.SEASON, "200", 1, null, null, null, null, null, 1999, null);

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("releaseYear must not be provided when type is SEASON");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Episode And ReleaseYear Is Present")
    void shouldThrowBadRequestExceptionWhenTypeIsEpisodeAndReleaseYearIsPresent() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.EPISODE, "200", 1, 3, null, null, null, null, 1999, null);

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("releaseYear must not be provided when type is EPISODE");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Season And Countries Is Present")
    void shouldThrowBadRequestExceptionWhenTypeIsSeasonAndCountriesIsPresent() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.SEASON, "200", 1, null, null, null, null, null, null, List.of("US"));

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("countries must not be provided when type is SEASON");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Episode And Countries Is Present")
    void shouldThrowBadRequestExceptionWhenTypeIsEpisodeAndCountriesIsPresent() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.EPISODE, "200", 1, 3, null, null, null, null, null, List.of("US"));

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("countries must not be provided when type is EPISODE");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw ConflictException - When Existing Content Has A Different ReleaseYear Value")
    void shouldThrowConflictExceptionWhenExistingContentHasADifferentReleaseYearValue() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("301", ContentType.MOVIE, null, null, null, null, null, null, null, 1999, null);
        Content existing = Content.builder().id(UUID.randomUUID()).tmdbId("301").type(ContentType.MOVIE).releaseYear(2005).build();

        when(contentRepository.findByTmdbIdAndType("301", ContentType.MOVIE)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(ConflictException.class)
                .hasMessage("This content is already registered with a different releaseYear value");

        verify(contentMapper, never()).contentToContentRefDto(any());
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw ConflictException - When Existing Content Has A Different Countries Value")
    void shouldThrowConflictExceptionWhenExistingContentHasADifferentCountriesValue() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("302", ContentType.SERIES, null, null, null, null, null, null, null, null, List.of("US"));
        Content existing = Content.builder().id(UUID.randomUUID()).tmdbId("302").type(ContentType.SERIES).countries(List.of("GB")).build();

        when(contentRepository.findByTmdbIdAndType("302", ContentType.SERIES)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(ConflictException.class)
                .hasMessage("This content is already registered with a different countries value");

        verify(contentMapper, never()).contentToContentRefDto(any());
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Not Throw Conflict - When Genres Are Resent In A Different Order")
    void shouldNotThrowConflictWhenGenresAreResentInADifferentOrder() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("300", ContentType.SERIES, null, null, null, null, null, null, List.of("Thriller", "Drama"));
        Content existing = Content.builder().id(UUID.randomUUID()).tmdbId("300").type(ContentType.SERIES).genres(List.of("Drama", "Thriller")).build();
        ContentRefDTO responseDto = new ContentRefDTO(existing.getId(), "300", ContentType.SERIES, null, null, null, null, null, null, null, null, List.of("Drama", "Thriller"));

        when(contentRepository.findByTmdbIdAndType("300", ContentType.SERIES)).thenReturn(Optional.of(existing));
        when(contentMapper.contentToContentRefDto(existing)).thenReturn(responseDto);

        ContentRefDTO result = contentService.getOrCreateReference(dto);

        assertThat(result).isEqualTo(responseDto);
    }

    private DataIntegrityViolationException buildDataIntegrityViolationException(String constraintName) {
        ConstraintViolationException cve = new ConstraintViolationException(
                "constraint violated",
                null,
                constraintName
        );
        return new DataIntegrityViolationException("db error", cve);
    }

}
