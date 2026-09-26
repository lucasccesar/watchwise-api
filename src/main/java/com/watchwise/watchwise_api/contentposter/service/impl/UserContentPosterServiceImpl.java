package com.watchwise.watchwise_api.contentposter.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.validation.TmdbPosterUrlPolicy;
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
    public UserContentPosterResponseDTO upsert(UUID userId, UUID contentId, String customPosterUrl) {
        validatePosterUrl(customPosterUrl);

        contentRepository.findById(contentId)
                .orElseThrow(() -> new NotFoundException("Content not found"));

        userContentPosterRepository.upsert(UUID.randomUUID(), userId, contentId, customPosterUrl);

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
                .collect(Collectors.toMap(
                        UserContentPosterRepository.SeriesPosterProjection::getSeriesTmdbId,
                        UserContentPosterRepository.SeriesPosterProjection::getCustomPosterUrl));
    }

    private void validatePosterUrl(String customPosterUrl) {
        if (!TmdbPosterUrlPolicy.isValid(customPosterUrl)) {
            throw new BadRequestException("customPosterUrl must be a TMDB w342 poster URL");
        }
    }
}
