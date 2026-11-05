package com.watchwise.watchwise_api.contentposter.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.validation.TmdbPosterPathPolicy;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.contentposter.dto.UserContentPosterResponseDTO;
import com.watchwise.watchwise_api.contentposter.repository.UserContentPosterRepository;
import com.watchwise.watchwise_api.contentposter.service.UserContentPosterService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserContentPosterServiceImpl implements UserContentPosterService {

    private final UserContentPosterRepository userContentPosterRepository;
    private final ContentRepository contentRepository;

    @Override
    @Transactional
    public UserContentPosterResponseDTO upsert(UUID userId, UUID contentId, String customPosterPath) {
        validatePosterPath(customPosterPath);

        contentRepository.findById(contentId)
                .orElseThrow(() -> new NotFoundException("Content not found"));

        userContentPosterRepository.upsert(UUID.randomUUID(), userId, contentId, customPosterPath);

        return userContentPosterRepository.findByUserIdAndContentId(userId, contentId)
                .map(poster -> new UserContentPosterResponseDTO(
                        contentId,
                        poster.getCustomPosterUrl(),
                        poster.getUpdatedAt()))
                .orElseThrow(() -> new IllegalStateException("User content poster was not persisted"));
    }

    @Override
    @Transactional
    public void delete(UUID userId, UUID contentId) {
        userContentPosterRepository.deleteByUserIdAndContentId(userId, contentId);
    }

    @Override
    public Map<UUID, String> findByUserAndContentIds(UUID userId, Collection<UUID> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) {
            return Map.of();
        }

        return userContentPosterRepository.findByUserIdAndContentIdIn(userId, contentIds).stream()
                .filter(projection -> TmdbPosterPathPolicy.isValid(projection.getCustomPosterUrl()))
                .collect(Collectors.toMap(
                        UserContentPosterRepository.ContentPosterProjection::getContentId,
                        UserContentPosterRepository.ContentPosterProjection::getCustomPosterUrl));
    }

    @Override
    public Map<UserContentPosterKey, String> findByUserAndContentPairs(Collection<UserContentPosterKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }

        var requestedKeys = new LinkedHashSet<>(keys);
        var userIds = requestedKeys.stream()
                .map(UserContentPosterKey::userId)
                .distinct()
                .toList();
        var contentIds = requestedKeys.stream()
                .map(UserContentPosterKey::contentId)
                .distinct()
                .toList();

        return userContentPosterRepository.findByUserIdInAndContentIdIn(userIds, contentIds).stream()
                .filter(projection -> TmdbPosterPathPolicy.isValid(projection.getCustomPosterUrl()))
                .map(projection -> Map.entry(
                        new UserContentPosterKey(projection.getUserId(), projection.getContentId()),
                        projection.getCustomPosterUrl()))
                .filter(entry -> requestedKeys.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public Map<String, String> findSeriesPosters(UUID userId, Collection<String> seriesTmdbIds) {
        if (seriesTmdbIds == null || seriesTmdbIds.isEmpty()) {
            return Map.of();
        }

        return userContentPosterRepository.findByUserIdAndSeriesTmdbIdIn(userId, seriesTmdbIds).stream()
                .filter(projection -> TmdbPosterPathPolicy.isValid(projection.getCustomPosterUrl()))
                .collect(Collectors.toMap(
                        UserContentPosterRepository.SeriesPosterProjection::getSeriesTmdbId,
                        UserContentPosterRepository.SeriesPosterProjection::getCustomPosterUrl));
    }

    private void validatePosterPath(String customPosterPath) {
        if (!TmdbPosterPathPolicy.isValid(customPosterPath)) {
            throw new BadRequestException(
                    "customPosterUrl must be a TMDB poster file path beginning with a single slash");
        }
    }
}
