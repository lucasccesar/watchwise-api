package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

    @InjectMocks
    private ContentServiceImpl contentService;

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Null")
    void shouldThrowBadRequestExceptionWhenTypeIsNull() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, null, null, null, null);

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Type must be provided");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Movie And TmdbId Is Missing")
    void shouldThrowBadRequestExceptionWhenTypeIsMovieAndTmdbIdIsMissing() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.MOVIE, null, null, null);

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("tmdbId must be provided when type is MOVIE or SERIES");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Series And TmdbId Is Empty")
    void shouldThrowBadRequestExceptionWhenTypeIsSeriesAndTmdbIdIsEmpty() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("", ContentType.SERIES, null, null, null);

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("tmdbId must be provided when type is MOVIE or SERIES");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Movie And SeriesTmdbId Is Present")
    void shouldThrowBadRequestExceptionWhenTypeIsMovieAndSeriesTmdbIdIsPresent() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("100", ContentType.MOVIE, "200", null, null);

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("seriesTmdbId, seasonNumber and episodeNumber must not be provided when type is MOVIE or SERIES");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Season And SeriesTmdbId Is Missing")
    void shouldThrowBadRequestExceptionWhenTypeIsSeasonAndSeriesTmdbIdIsMissing() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.SEASON, null, 1, null);

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("seriesTmdbId and seasonNumber must be provided when type is SEASON");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Season And SeasonNumber Is Missing")
    void shouldThrowBadRequestExceptionWhenTypeIsSeasonAndSeasonNumberIsMissing() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.SEASON, "200", null, null);

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("seriesTmdbId and seasonNumber must be provided when type is SEASON");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Season And TmdbId Is Present")
    void shouldThrowBadRequestExceptionWhenTypeIsSeasonAndTmdbIdIsPresent() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("100", ContentType.SEASON, "200", 1, null);

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("tmdbId and episodeNumber must not be provided when type is SEASON");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Season And EpisodeNumber Is Present")
    void shouldThrowBadRequestExceptionWhenTypeIsSeasonAndEpisodeNumberIsPresent() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.SEASON, "200", 1, 3);

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("tmdbId and episodeNumber must not be provided when type is SEASON");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Episode And EpisodeNumber Is Missing")
    void shouldThrowBadRequestExceptionWhenTypeIsEpisodeAndEpisodeNumberIsMissing() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.EPISODE, "200", 1, null);

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("seriesTmdbId, seasonNumber and episodeNumber must be provided when type is EPISODE");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Episode And SeriesTmdbId Is Missing")
    void shouldThrowBadRequestExceptionWhenTypeIsEpisodeAndSeriesTmdbIdIsMissing() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.EPISODE, null, 1, 3);

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("seriesTmdbId, seasonNumber and episodeNumber must be provided when type is EPISODE");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Throw BadRequestException - When Type Is Episode And TmdbId Is Present")
    void shouldThrowBadRequestExceptionWhenTypeIsEpisodeAndTmdbIdIsPresent() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("100", ContentType.EPISODE, "200", 1, 3);

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("tmdbId must not be provided when type is EPISODE");

        verifyNoInteractions(contentRepository, contentMapper);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Return Existing ContentRefDTO - When Movie Reference Already Exists")
    void shouldReturnExistingContentRefDtoWhenMovieReferenceAlreadyExists() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("100", ContentType.MOVIE, null, null, null);
        Content existing = Content.builder().id(UUID.randomUUID()).tmdbId("100").type(ContentType.MOVIE).build();
        ContentRefDTO responseDto = new ContentRefDTO(existing.getId(), "100", ContentType.MOVIE, null, null, null, null, null);

        when(contentRepository.findByTmdbIdAndType("100", ContentType.MOVIE)).thenReturn(Optional.of(existing));
        when(contentMapper.contentToContentRefDto(existing)).thenReturn(responseDto);

        ContentRefDTO result = contentService.getOrCreateReference(dto);

        assertThat(result).isEqualTo(responseDto);
        verify(contentRepository, never()).save(any());
        verify(contentMapper, never()).contentRefCreationDtoToContent(any());
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Create And Return ContentRefDTO - When Movie Reference Does Not Exist")
    void shouldCreateAndReturnContentRefDtoWhenMovieReferenceDoesNotExist() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("100", ContentType.MOVIE, null, null, null);
        Content mapped = Content.builder().tmdbId("100").type(ContentType.MOVIE).build();
        Content saved = Content.builder().id(UUID.randomUUID()).tmdbId("100").type(ContentType.MOVIE).build();
        ContentRefDTO responseDto = new ContentRefDTO(saved.getId(), "100", ContentType.MOVIE, null, null, null, null, null);

        when(contentRepository.findByTmdbIdAndType("100", ContentType.MOVIE)).thenReturn(Optional.empty());
        when(contentMapper.contentRefCreationDtoToContent(dto)).thenReturn(mapped);
        when(contentRepository.save(mapped)).thenReturn(saved);
        when(contentMapper.contentToContentRefDto(saved)).thenReturn(responseDto);

        ContentRefDTO result = contentService.getOrCreateReference(dto);

        assertThat(result).isEqualTo(responseDto);
        verify(contentRepository).save(mapped);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Set CreatedAt And UpdatedAt - When Creating New Reference")
    void shouldSetCreatedAtAndUpdatedAtWhenCreatingNewReference() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("100", ContentType.MOVIE, null, null, null);
        Content mapped = Content.builder().tmdbId("100").type(ContentType.MOVIE).build();

        when(contentRepository.findByTmdbIdAndType("100", ContentType.MOVIE)).thenReturn(Optional.empty());
        when(contentMapper.contentRefCreationDtoToContent(dto)).thenReturn(mapped);
        when(contentRepository.save(mapped)).thenReturn(mapped);
        when(contentMapper.contentToContentRefDto(mapped)).thenReturn(
                new ContentRefDTO(null, "100", ContentType.MOVIE, null, null, null, null, null));

        ArgumentCaptor<Content> captor = ArgumentCaptor.forClass(Content.class);

        contentService.getOrCreateReference(dto);

        verify(contentRepository).save(captor.capture());
        assertThat(captor.getValue().getCreatedAt()).isNotNull();
        assertThat(captor.getValue().getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Return Existing ContentRefDTO - When Series Reference Already Exists")
    void shouldReturnExistingContentRefDtoWhenSeriesReferenceAlreadyExists() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("300", ContentType.SERIES, null, null, null);
        Content existing = Content.builder().id(UUID.randomUUID()).tmdbId("300").type(ContentType.SERIES).build();
        ContentRefDTO responseDto = new ContentRefDTO(existing.getId(), "300", ContentType.SERIES, null, null, null, null, null);

        when(contentRepository.findByTmdbIdAndType("300", ContentType.SERIES)).thenReturn(Optional.of(existing));
        when(contentMapper.contentToContentRefDto(existing)).thenReturn(responseDto);

        ContentRefDTO result = contentService.getOrCreateReference(dto);

        assertThat(result).isEqualTo(responseDto);
        verify(contentRepository, never()).save(any());
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Return Existing ContentRefDTO - When Season Reference Already Exists")
    void shouldReturnExistingContentRefDtoWhenSeasonReferenceAlreadyExists() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.SEASON, "200", 1, null);
        Content existing = Content.builder().id(UUID.randomUUID()).seriesTmdbId("200").seasonNumber(1).type(ContentType.SEASON).build();
        ContentRefDTO responseDto = new ContentRefDTO(existing.getId(), null, ContentType.SEASON, "200", 1, null, null, null);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("200", 1, null, ContentType.SEASON))
                .thenReturn(Optional.of(existing));
        when(contentMapper.contentToContentRefDto(existing)).thenReturn(responseDto);

        ContentRefDTO result = contentService.getOrCreateReference(dto);

        assertThat(result).isEqualTo(responseDto);
        verify(contentRepository, never()).save(any());
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Query With Null EpisodeNumber - When Type Is Season")
    void shouldQueryWithNullEpisodeNumberWhenTypeIsSeason() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.SEASON, "200", 1, null);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType(
                eq("200"), eq(1), isNull(), eq(ContentType.SEASON)))
                .thenReturn(Optional.empty());
        when(contentMapper.contentRefCreationDtoToContent(dto)).thenReturn(Content.builder().build());
        when(contentRepository.save(any())).thenReturn(Content.builder().build());
        when(contentMapper.contentToContentRefDto(any())).thenReturn(
                new ContentRefDTO(null, null, ContentType.SEASON, "200", 1, null, null, null));

        contentService.getOrCreateReference(dto);

        verify(contentRepository).findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType(
                eq("200"), eq(1), isNull(), eq(ContentType.SEASON));
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Create And Return ContentRefDTO - When Season Reference Does Not Exist")
    void shouldCreateAndReturnContentRefDtoWhenSeasonReferenceDoesNotExist() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.SEASON, "200", 1, null);
        Content mapped = Content.builder().seriesTmdbId("200").seasonNumber(1).type(ContentType.SEASON).build();
        Content saved = Content.builder().id(UUID.randomUUID()).seriesTmdbId("200").seasonNumber(1).type(ContentType.SEASON).build();
        ContentRefDTO responseDto = new ContentRefDTO(saved.getId(), null, ContentType.SEASON, "200", 1, null, null, null);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("200", 1, null, ContentType.SEASON))
                .thenReturn(Optional.empty());
        when(contentMapper.contentRefCreationDtoToContent(dto)).thenReturn(mapped);
        when(contentRepository.save(mapped)).thenReturn(saved);
        when(contentMapper.contentToContentRefDto(saved)).thenReturn(responseDto);

        ContentRefDTO result = contentService.getOrCreateReference(dto);

        assertThat(result).isEqualTo(responseDto);
        verify(contentRepository).save(mapped);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Return Existing ContentRefDTO - When Episode Reference Already Exists")
    void shouldReturnExistingContentRefDtoWhenEpisodeReferenceAlreadyExists() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.EPISODE, "200", 1, 3);
        Content existing = Content.builder().id(UUID.randomUUID()).seriesTmdbId("200").seasonNumber(1).episodeNumber(3).type(ContentType.EPISODE).build();
        ContentRefDTO responseDto = new ContentRefDTO(existing.getId(), null, ContentType.EPISODE, "200", 1, 3, null, null);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("200", 1, 3, ContentType.EPISODE))
                .thenReturn(Optional.of(existing));
        when(contentMapper.contentToContentRefDto(existing)).thenReturn(responseDto);

        ContentRefDTO result = contentService.getOrCreateReference(dto);

        assertThat(result).isEqualTo(responseDto);
        verify(contentRepository, never()).save(any());
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Create And Return ContentRefDTO - When Episode Reference Does Not Exist")
    void shouldCreateAndReturnContentRefDtoWhenEpisodeReferenceDoesNotExist() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.EPISODE, "200", 1, 3);
        Content mapped = Content.builder().seriesTmdbId("200").seasonNumber(1).episodeNumber(3).type(ContentType.EPISODE).build();
        Content saved = Content.builder().id(UUID.randomUUID()).seriesTmdbId("200").seasonNumber(1).episodeNumber(3).type(ContentType.EPISODE).build();
        ContentRefDTO responseDto = new ContentRefDTO(saved.getId(), null, ContentType.EPISODE, "200", 1, 3, null, null);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("200", 1, 3, ContentType.EPISODE))
                .thenReturn(Optional.empty());
        when(contentMapper.contentRefCreationDtoToContent(dto)).thenReturn(mapped);
        when(contentRepository.save(mapped)).thenReturn(saved);
        when(contentMapper.contentToContentRefDto(saved)).thenReturn(responseDto);

        ContentRefDTO result = contentService.getOrCreateReference(dto);

        assertThat(result).isEqualTo(responseDto);
        verify(contentRepository).save(mapped);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Return Existing ContentRefDTO - When Save Throws DataIntegrityViolationException Due To Concurrent Creation")
    void shouldReturnExistingContentRefDtoWhenSaveThrowsDataIntegrityViolationExceptionDueToConcurrentCreation() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("100", ContentType.MOVIE, null, null, null);
        Content mapped = Content.builder().tmdbId("100").type(ContentType.MOVIE).build();
        Content existing = Content.builder().id(UUID.randomUUID()).tmdbId("100").type(ContentType.MOVIE).build();
        ContentRefDTO responseDto = new ContentRefDTO(existing.getId(), "100", ContentType.MOVIE, null, null, null, null, null);

        when(contentRepository.findByTmdbIdAndType("100", ContentType.MOVIE))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(contentMapper.contentRefCreationDtoToContent(dto)).thenReturn(mapped);
        when(contentRepository.save(mapped))
                .thenThrow(buildDataIntegrityViolationException("uq_contents_tmdb_id_type"));
        when(contentMapper.contentToContentRefDto(existing)).thenReturn(responseDto);

        ContentRefDTO result = contentService.getOrCreateReference(dto);

        assertThat(result).isEqualTo(responseDto);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Rethrow DataIntegrityViolationException - When Save Fails And Reference Is Still Not Found")
    void shouldRethrowDataIntegrityViolationExceptionWhenSaveFailsAndReferenceIsStillNotFound() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("100", ContentType.MOVIE, null, null, null);
        Content mapped = Content.builder().tmdbId("100").type(ContentType.MOVIE).build();
        DataIntegrityViolationException exception = buildDataIntegrityViolationException("uq_contents_tmdb_id_type");

        when(contentRepository.findByTmdbIdAndType("100", ContentType.MOVIE)).thenReturn(Optional.empty());
        when(contentMapper.contentRefCreationDtoToContent(dto)).thenReturn(mapped);
        when(contentRepository.save(mapped)).thenThrow(exception);

        assertThatThrownBy(() -> contentService.getOrCreateReference(dto))
                .isSameAs(exception);

        verify(contentMapper, never()).contentToContentRefDto(any());
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