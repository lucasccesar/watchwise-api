package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
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

    @Override
    public ContentRefDTO getOrCreateReference(ContentRefCreationDTO contentRefCreationDTO) {
        validate(contentRefCreationDTO);

        Optional<Content> existing = findExisting(contentRefCreationDTO);
        if (existing.isPresent()) {
            return contentMapper.contentToContentRefDto(existing.get());
        }

        Content content = contentMapper.contentRefCreationDtoToContent(contentRefCreationDTO);
        LocalDateTime now = LocalDateTime.now();
        content.setCreatedAt(now);
        content.setUpdatedAt(now);

        try {
            return contentMapper.contentToContentRefDto(contentRepository.save(content));
        } catch (DataIntegrityViolationException e) {
            return contentMapper.contentToContentRefDto(resolveConcurrentCreation(contentRefCreationDTO, e));
        }
    }

    private Content resolveConcurrentCreation(ContentRefCreationDTO dto, DataIntegrityViolationException e) {
        return findExisting(dto).orElseThrow(() -> e);
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
            }
            case SEASON -> {
                if (StringUtils.isEmpty(dto.seriesTmdbId()) || dto.seasonNumber() == null) {
                    throw new BadRequestException("seriesTmdbId and seasonNumber must be provided when type is SEASON");
                }
                if (dto.tmdbId() != null || dto.episodeNumber() != null) {
                    throw new BadRequestException("tmdbId and episodeNumber must not be provided when type is SEASON");
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