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
import com.watchwise.watchwise_api.content.service.ContentService;
import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ContentServiceImpl implements ContentService {

    private final ContentMapper contentMapper;
    private final ContentRepository contentRepository;
    private final NewTransactionExecutor newTransactionExecutor;

    @Override
    public ContentRefDTO getOrCreateReference(ContentRefCreationDTO contentRefCreationDTO) {
        ContentRefCreationDTO normalized = normalize(contentRefCreationDTO);
        validate(normalized);

        Optional<Content> existing = findExisting(normalized);
        if (existing.isPresent()) {
            return contentMapper.contentToContentRefDto(existing.get());
        }

        if (normalized.type() == ContentType.SEASON && Boolean.TRUE.equals(normalized.isSeriesFinale())) {
            clearPreviousSeriesFinale(normalized.seriesTmdbId(), normalized.seasonNumber());
        }

        try {
            Content saved = newTransactionExecutor.runInNewTransaction(() -> {
                Content content = contentMapper.contentRefCreationDtoToContent(normalized);
                LocalDateTime now = LocalDateTime.now();
                content.setCreatedAt(now);
                content.setUpdatedAt(now);
                return contentRepository.saveAndFlush(content);
            });
            return contentMapper.contentToContentRefDto(saved);
        } catch (DataIntegrityViolationException e) {
            return contentMapper.contentToContentRefDto(resolveConcurrentCreation(normalized, e));
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
                dto.isSeriesFinale()
        );
    }

    private String trimOrNull(String value) {
        return value == null ? null : value.trim();
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

    private Content resolveConcurrentCreation(ContentRefCreationDTO dto, DataIntegrityViolationException e) {
        String constraintName = extractConstraintName(e);
        if ("uq_contents_season_finale".equals(constraintName)) {
            throw new ConflictException("Another episode is already marked as this season's finale");
        }
        if ("uq_contents_series_finale".equals(constraintName)) {
            throw new ConflictException("Another season is already marked as this series' finale");
        }
        return findExisting(dto).orElseThrow(() -> e);
    }

    private String extractConstraintName(DataIntegrityViolationException e) {
        Throwable cause = e.getCause();
        if (cause instanceof org.hibernate.exception.ConstraintViolationException cve) {
            return cve.getConstraintName();
        }
        return null;
    }

    private Optional<Content> findExisting(ContentRefCreationDTO dto) {
        if (dto.type() == ContentType.MOVIE || dto.type() == ContentType.SERIES) {
            return contentRepository.findByTmdbIdAndType(dto.tmdbId(), dto.type());
        }
        return contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType(
                dto.seriesTmdbId(), dto.seasonNumber(), dto.episodeNumber(), dto.type());
    }

    private void validate(ContentRefCreationDTO dto) {
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
            }
            case EPISODE -> {
                if (StringUtils.isEmpty(dto.seriesTmdbId()) || dto.seasonNumber() == null || dto.episodeNumber() == null) {
                    throw new BadRequestException("seriesTmdbId, seasonNumber and episodeNumber must be provided when type is EPISODE");
                }
                if (dto.tmdbId() != null) {
                    throw new BadRequestException("tmdbId must not be provided when type is EPISODE");
                }
            }
        }
    }

}