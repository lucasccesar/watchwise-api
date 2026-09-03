package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbGenre;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbProductionCountry;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentService;
import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ContentServiceImpl implements ContentService {

    private static final String EXISTENCE_CHECK_LANGUAGE = "en-US";

    private final ContentMapper contentMapper;
    private final ContentRepository contentRepository;
    private final NewTransactionExecutor newTransactionExecutor;
    private final TmdbClient tmdbClient;

    @Override
    public ContentRefDTO getOrCreateReference(ContentRefCreationDTO contentRefCreationDTO) {
        return getOrCreateReference(contentRefCreationDTO, false);
    }

    @Override
    public ContentRefDTO getOrCreateReference(ContentRefCreationDTO contentRefCreationDTO, boolean trustedRuntimeMinutes) {
        ContentRefCreationDTO normalized = normalize(contentRefCreationDTO);
        validate(normalized, trustedRuntimeMinutes);

        Optional<Content> existing = findExisting(normalized);
        if (existing.isPresent()) {
            ContentRefCreationDTO enriched = backfillMissingTmdbMetadata(normalized, existing.get());
            return contentMapper.contentToContentRefDto(reconcileExisting(existing.get(), enriched, trustedRuntimeMinutes));
        }

        ContentRefCreationDTO enriched = resolveNewContentMetadata(normalized, trustedRuntimeMinutes);

        try {
            Content saved = newTransactionExecutor.runInNewTransaction(() -> {
                if (enriched.type() == ContentType.SEASON && Boolean.TRUE.equals(enriched.isSeriesFinale())) {
                    clearPreviousSeriesFinale(enriched.seriesTmdbId(), enriched.seasonNumber());
                }
                if (enriched.type() == ContentType.EPISODE && Boolean.TRUE.equals(enriched.isSeasonFinale())) {
                    clearPreviousSeasonFinale(enriched.seriesTmdbId(), enriched.seasonNumber(), enriched.episodeNumber());
                }

                Content content = contentMapper.contentRefCreationDtoToContent(enriched);
                LocalDateTime now = LocalDateTime.now();
                content.setCreatedAt(now);
                content.setUpdatedAt(now);
                return contentRepository.saveAndFlush(content);
            });
            return contentMapper.contentToContentRefDto(saved);
        } catch (DataIntegrityViolationException e) {
            return contentMapper.contentToContentRefDto(resolveConcurrentCreation(enriched, e, trustedRuntimeMinutes));
        }
    }

    private ContentRefCreationDTO normalize(ContentRefCreationDTO dto) {
        return new ContentRefCreationDTO(
                trimOrNull(dto.tmdbId()),
                dto.type(),
                trimOrNull(dto.seriesTmdbId()),
                dto.seasonNumber(),
                dto.episodeNumber(),
                dto.isSeasonFinale(),
                dto.isSeriesFinale(),
                dto.runtimeMinutes(),
                normalizeGenres(dto.genres()),
                dto.releaseYear(),
                normalizeCountries(dto.countries())
        );
    }

    private String trimOrNull(String value) {
        return value == null ? null : value.trim();
    }

    private List<String> normalizeGenres(List<String> genres) {
        if (genres == null) {
            return null;
        }
        return genres.stream()
                .map(String::trim)
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private List<String> normalizeCountries(List<String> countries) {
        if (countries == null) {
            return null;
        }
        return countries.stream()
                .map(country -> country.trim().toUpperCase())
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private void clearPreviousSeriesFinale(String seriesTmdbId, Integer newSeasonNumber) {
        contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue(seriesTmdbId, ContentType.SEASON)
                .filter(previous -> previous.getSeasonNumber() < newSeasonNumber)
                .ifPresent(previous -> {
                    previous.setIsSeriesFinale(false);
                    previous.setUpdatedAt(LocalDateTime.now());
                    contentRepository.saveAndFlush(previous);
                });
    }

    private void clearPreviousSeasonFinale(String seriesTmdbId, Integer seasonNumber, Integer newEpisodeNumber) {
        contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue(seriesTmdbId, seasonNumber, ContentType.EPISODE)
                .filter(previous -> previous.getEpisodeNumber() < newEpisodeNumber)
                .ifPresent(previous -> {
                    previous.setIsSeasonFinale(false);
                    if (Boolean.TRUE.equals(previous.getIsSeriesFinale())) {
                        previous.setIsSeriesFinale(false);
                    }
                    previous.setUpdatedAt(LocalDateTime.now());
                    contentRepository.saveAndFlush(previous);
                });
    }

    private Content reconcileExisting(Content existing, ContentRefCreationDTO normalized, boolean trustedRuntimeMinutes) {
        assertNoMetadataMismatch(existing, normalized, trustedRuntimeMinutes);

        boolean backfillSeasonFinale = existing.getIsSeasonFinale() == null && Boolean.TRUE.equals(normalized.isSeasonFinale());
        boolean backfillSeriesFinale = existing.getIsSeriesFinale() == null && Boolean.TRUE.equals(normalized.isSeriesFinale());
        boolean updateRuntimeMinutes = normalized.runtimeMinutes() != null
                && (existing.getRuntimeMinutes() == null
                        || (trustedRuntimeMinutes && !normalized.runtimeMinutes().equals(existing.getRuntimeMinutes())));
        boolean backfillGenres = (existing.getGenres() == null || existing.getGenres().isEmpty())
                && normalized.genres() != null && !normalized.genres().isEmpty();
        boolean backfillReleaseYear = existing.getReleaseYear() == null && normalized.releaseYear() != null;
        boolean backfillCountries = (existing.getCountries() == null || existing.getCountries().isEmpty())
                && normalized.countries() != null && !normalized.countries().isEmpty();
        if (!backfillSeasonFinale && !backfillSeriesFinale && !updateRuntimeMinutes
                && !backfillGenres && !backfillReleaseYear && !backfillCountries) {
            return existing;
        }

        UUID existingId = existing.getId();
        try {
            return newTransactionExecutor.runInNewTransaction(() -> {
                Content fresh = contentRepository.findById(existingId).orElseThrow();
                if (backfillSeasonFinale) {
                    clearPreviousSeasonFinale(normalized.seriesTmdbId(), normalized.seasonNumber(), normalized.episodeNumber());
                    fresh.setIsSeasonFinale(true);
                }
                if (backfillSeriesFinale) {
                    if (fresh.getType() == ContentType.SEASON) {
                        clearPreviousSeriesFinale(normalized.seriesTmdbId(), normalized.seasonNumber());
                    }
                    fresh.setIsSeriesFinale(true);
                }
                if (updateRuntimeMinutes) {
                    fresh.setRuntimeMinutes(normalized.runtimeMinutes());
                }
                if (backfillGenres) {
                    fresh.setGenres(normalized.genres());
                }
                if (backfillReleaseYear) {
                    fresh.setReleaseYear(normalized.releaseYear());
                }
                if (backfillCountries) {
                    fresh.setCountries(normalized.countries());
                }
                fresh.setUpdatedAt(LocalDateTime.now());
                return contentRepository.saveAndFlush(fresh);
            });
        } catch (DataIntegrityViolationException e) {
            return existing;
        }
    }

    private void assertNoMetadataMismatch(Content existing, ContentRefCreationDTO normalized, boolean trustedRuntimeMinutes) {
        if (normalized.isSeasonFinale() != null && existing.getIsSeasonFinale() != null
                && !normalized.isSeasonFinale().equals(existing.getIsSeasonFinale())) {
            throw new ConflictException("This content is already registered with a different isSeasonFinale value");
        }
        if (normalized.isSeriesFinale() != null && existing.getIsSeriesFinale() != null
                && !normalized.isSeriesFinale().equals(existing.getIsSeriesFinale())) {
            throw new ConflictException("This content is already registered with a different isSeriesFinale value");
        }
        if (!trustedRuntimeMinutes && normalized.runtimeMinutes() != null && existing.getRuntimeMinutes() != null
                && !normalized.runtimeMinutes().equals(existing.getRuntimeMinutes())) {
            throw new ConflictException("This content is already registered with a different runtimeMinutes value");
        }
        if (normalized.genres() != null && !normalized.genres().isEmpty()
                && existing.getGenres() != null && !existing.getGenres().isEmpty()
                && !normalized.genres().equals(existing.getGenres())) {
            throw new ConflictException("This content is already registered with a different genres value");
        }
        if (normalized.releaseYear() != null && existing.getReleaseYear() != null
                && !normalized.releaseYear().equals(existing.getReleaseYear())) {
            throw new ConflictException("This content is already registered with a different releaseYear value");
        }
        if (normalized.countries() != null && !normalized.countries().isEmpty()
                && existing.getCountries() != null && !existing.getCountries().isEmpty()
                && !normalized.countries().equals(existing.getCountries())) {
            throw new ConflictException("This content is already registered with a different countries value");
        }
    }

    private Content resolveConcurrentCreation(ContentRefCreationDTO dto, DataIntegrityViolationException e, boolean trustedRuntimeMinutes) {
        Optional<Content> existing = findExisting(dto);
        if (existing.isPresent()) {
            return reconcileExisting(existing.get(), dto, trustedRuntimeMinutes);
        }

        String constraintName = extractConstraintName(e);
        if ("uq_contents_season_finale".equals(constraintName)) {
            throw new ConflictException("Another episode is already marked as this season's finale");
        }
        if ("uq_contents_series_finale".equals(constraintName)) {
            throw new ConflictException("Another season is already marked as this series' finale");
        }
        throw e;
    }

    private String extractConstraintName(DataIntegrityViolationException e) {
        Throwable cause = e.getCause();
        if (cause instanceof org.hibernate.exception.ConstraintViolationException cve) {
            return cve.getConstraintName();
        }
        return null;
    }

    private ContentRefCreationDTO resolveNewContentMetadata(ContentRefCreationDTO dto, boolean trustedRuntimeMinutes) {
        return switch (dto.type()) {
            case MOVIE -> withMovieMetadata(dto,
                    requireFound(tmdbClient.getMovieFullDetails(dto.tmdbId(), EXISTENCE_CHECK_LANGUAGE), "movie"));
            case SERIES -> withSeriesMetadata(dto,
                    requireFound(tmdbClient.getTvFullDetails(dto.tmdbId(), EXISTENCE_CHECK_LANGUAGE), "series"));
            case SEASON -> {
                requireFound(tmdbClient.getTvFullDetails(dto.seriesTmdbId(), EXISTENCE_CHECK_LANGUAGE), "series");
                yield dto;
            }
            case EPISODE -> {
                requireFound(tmdbClient.getTvFullDetails(dto.seriesTmdbId(), EXISTENCE_CHECK_LANGUAGE), "series");
                if (trustedRuntimeMinutes) {
                    yield dto;
                }
                TmdbEpisodeFullDetails episode = requireFound(tmdbClient.getEpisodeFullDetails(
                        dto.seriesTmdbId(), dto.seasonNumber(), dto.episodeNumber(), EXISTENCE_CHECK_LANGUAGE), "episode");
                yield withEpisodeRuntime(dto, episode.runtime());
            }
        };
    }

    private ContentRefCreationDTO backfillMissingTmdbMetadata(ContentRefCreationDTO dto, Content existing) {
        return switch (dto.type()) {
            case MOVIE -> hasMovieOrSeriesMetadata(existing) && existing.getRuntimeMinutes() != null
                    ? dto
                    : tmdbClient.getMovieFullDetails(dto.tmdbId(), EXISTENCE_CHECK_LANGUAGE).toOptional()
                            .map(details -> withMovieMetadata(dto, details)).orElse(dto);
            case SERIES -> hasMovieOrSeriesMetadata(existing)
                    ? dto
                    : tmdbClient.getTvFullDetails(dto.tmdbId(), EXISTENCE_CHECK_LANGUAGE).toOptional()
                            .map(details -> withSeriesMetadata(dto, details)).orElse(dto);
            case EPISODE -> existing.getRuntimeMinutes() != null
                    ? dto
                    : tmdbClient.getEpisodeFullDetails(dto.seriesTmdbId(), dto.seasonNumber(), dto.episodeNumber(), EXISTENCE_CHECK_LANGUAGE)
                            .toOptional().map(details -> withEpisodeRuntime(dto, details.runtime())).orElse(dto);
            case SEASON -> dto;
        };
    }

    private boolean hasMovieOrSeriesMetadata(Content content) {
        return content.getGenres() != null && !content.getGenres().isEmpty()
                && content.getReleaseYear() != null
                && content.getCountries() != null && !content.getCountries().isEmpty();
    }

    private ContentRefCreationDTO withMovieMetadata(ContentRefCreationDTO dto, TmdbMovieFullDetails details) {
        return new ContentRefCreationDTO(
                dto.tmdbId(), dto.type(), dto.seriesTmdbId(), dto.seasonNumber(), dto.episodeNumber(),
                dto.isSeasonFinale(), dto.isSeriesFinale(), details.runtime(),
                normalizeGenres(genreNames(details.genres())), releaseYearFromDate(details.releaseDate()),
                normalizeCountries(countryCodes(details.productionCountries())));
    }

    private ContentRefCreationDTO withSeriesMetadata(ContentRefCreationDTO dto, TmdbTvFullDetails details) {
        return new ContentRefCreationDTO(
                dto.tmdbId(), dto.type(), dto.seriesTmdbId(), dto.seasonNumber(), dto.episodeNumber(),
                dto.isSeasonFinale(), dto.isSeriesFinale(), dto.runtimeMinutes(),
                normalizeGenres(genreNames(details.genres())), releaseYearFromDate(details.firstAirDate()),
                normalizeCountries(countryCodes(details.productionCountries())));
    }

    private ContentRefCreationDTO withEpisodeRuntime(ContentRefCreationDTO dto, Integer runtimeMinutes) {
        if (runtimeMinutes == null) {
            return dto;
        }
        return new ContentRefCreationDTO(
                dto.tmdbId(), dto.type(), dto.seriesTmdbId(), dto.seasonNumber(), dto.episodeNumber(),
                dto.isSeasonFinale(), dto.isSeriesFinale(), runtimeMinutes, dto.genres(), dto.releaseYear(), dto.countries());
    }

    private List<String> genreNames(List<TmdbGenre> genres) {
        if (genres == null || genres.isEmpty()) {
            return null;
        }
        return genres.stream().map(TmdbGenre::name).toList();
    }

    private List<String> countryCodes(List<TmdbProductionCountry> countries) {
        if (countries == null || countries.isEmpty()) {
            return null;
        }
        return countries.stream().map(TmdbProductionCountry::isoCode).toList();
    }

    private Integer releaseYearFromDate(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date).getYear();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private <T> T requireFound(TmdbLookupResult<T> result, String subject) {
        if (result.isNotFound()) {
            throw new NotFoundException("No " + subject + " found on TMDB for the given id");
        }
        if (result.isUnavailable()) {
            throw new TmdbUnavailableException("TMDB is currently unavailable");
        }
        return result.toOptional().orElseThrow();
    }

    private Optional<Content> findExisting(ContentRefCreationDTO dto) {
        if (dto.type() == ContentType.MOVIE || dto.type() == ContentType.SERIES) {
            return contentRepository.findByTmdbIdAndType(dto.tmdbId(), dto.type());
        }
        return contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType(
                dto.seriesTmdbId(), dto.seasonNumber(), dto.episodeNumber(), dto.type());
    }

    private void validate(ContentRefCreationDTO dto, boolean trustedRuntimeMinutes) {
        if (dto.type() == null) {
            throw new BadRequestException("Type must be provided");
        }

        switch (dto.type()) {
            case MOVIE, SERIES -> {
                if (StringUtils.isEmpty(dto.tmdbId())) {
                    throw new BadRequestException("tmdbId must be provided when type is MOVIE or SERIES");
                }
                if (dto.seriesTmdbId() != null || dto.seasonNumber() != null || dto.episodeNumber() != null) {
                    throw new BadRequestException("seriesTmdbId, seasonNumber and episodeNumber must not be provided when type is MOVIE or SERIES");
                }
                if (dto.isSeasonFinale() != null || dto.isSeriesFinale() != null) {
                    throw new BadRequestException("isSeasonFinale and isSeriesFinale must not be provided when type is MOVIE or SERIES");
                }
                if (dto.runtimeMinutes() != null) {
                    throw new BadRequestException("runtimeMinutes must not be provided when type is MOVIE or SERIES, it is derived from TMDB");
                }
                if (dto.genres() != null) {
                    throw new BadRequestException("genres must not be provided when type is MOVIE or SERIES, it is derived from TMDB");
                }
                if (dto.releaseYear() != null) {
                    throw new BadRequestException("releaseYear must not be provided when type is MOVIE or SERIES, it is derived from TMDB");
                }
                if (dto.countries() != null) {
                    throw new BadRequestException("countries must not be provided when type is MOVIE or SERIES, it is derived from TMDB");
                }
            }
            case SEASON -> {
                if (StringUtils.isEmpty(dto.seriesTmdbId()) || dto.seasonNumber() == null) {
                    throw new BadRequestException("seriesTmdbId and seasonNumber must be provided when type is SEASON");
                }
                if (dto.tmdbId() != null || dto.episodeNumber() != null) {
                    throw new BadRequestException("tmdbId and episodeNumber must not be provided when type is SEASON");
                }
                if (dto.isSeasonFinale() != null) {
                    throw new BadRequestException("isSeasonFinale must not be provided when type is SEASON");
                }
                if (dto.runtimeMinutes() != null) {
                    throw new BadRequestException("runtimeMinutes must not be provided when type is SEASON");
                }
                if (dto.genres() != null) {
                    throw new BadRequestException("genres must not be provided when type is SEASON");
                }
                if (dto.releaseYear() != null) {
                    throw new BadRequestException("releaseYear must not be provided when type is SEASON");
                }
                if (dto.countries() != null) {
                    throw new BadRequestException("countries must not be provided when type is SEASON");
                }
            }
            case EPISODE -> {
                if (StringUtils.isEmpty(dto.seriesTmdbId()) || dto.seasonNumber() == null || dto.episodeNumber() == null) {
                    throw new BadRequestException("seriesTmdbId, seasonNumber and episodeNumber must be provided when type is EPISODE");
                }
                if (dto.tmdbId() != null) {
                    throw new BadRequestException("tmdbId must not be provided when type is EPISODE");
                }
                if (dto.genres() != null) {
                    throw new BadRequestException("genres must not be provided when type is EPISODE");
                }
                if (dto.releaseYear() != null) {
                    throw new BadRequestException("releaseYear must not be provided when type is EPISODE");
                }
                if (dto.countries() != null) {
                    throw new BadRequestException("countries must not be provided when type is EPISODE");
                }
                if (!trustedRuntimeMinutes && dto.runtimeMinutes() != null) {
                    throw new BadRequestException("runtimeMinutes must not be provided when type is EPISODE, it is derived from TMDB");
                }
            }
        }
    }

}
