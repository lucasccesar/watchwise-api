package com.watchwise.watchwise_api.diaryentry.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.diarycompletion.service.DiaryCompletionService;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryViewFilter;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryViewItemDTO;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryViewRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.user.service.UserVisibilityService;
import com.watchwise.watchwise_api.userblock.service.UserBlockAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DiaryViewServiceImpl implements com.watchwise.watchwise_api.diaryentry.service.DiaryViewService {

    private final UserRepository userRepository;
    private final UserBlockAccessService userBlockAccessService;
    private final UserVisibilityService userVisibilityService;
    private final DiaryCompletionService diaryCompletionService;
    private final DiaryViewRepository diaryViewRepository;
    private final ContentRepository contentRepository;
    private final ContentMapper contentMapper;
    private final PageRequestFactory pageRequestFactory;

    @Override
    public Page<DiaryViewItemDTO> getView(
            UUID viewerId,
            UUID ownerId,
            DiaryViewFilter filter,
            Integer pageNumber,
            Integer pageSize) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        userBlockAccessService.assertContentVisible(viewerId, ownerId);
        userVisibilityService.assertCanView(viewerId, ownerId);
        validate(filter);

        PageRequest pageRequest = pageRequestFactory.build(pageNumber, pageSize);
        diaryCompletionService.refreshStaleScopes(ownerId, java.time.Instant.now());
        Page<DiaryViewRepository.DiaryViewKey> keys = diaryViewRepository.findPage(
                ownerId,
                owner.getPreferredRegion(),
                owner.getPreferredLanguage(),
                java.time.Instant.now(),
                filter,
                pageRequest);
        Map<UUID, ContentRefDTO> contentById = contentRepository.findAllById(
                        keys.getContent().stream().map(DiaryViewRepository.DiaryViewKey::contentId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Content::getId, contentMapper::contentToContentRefDto));
        return keys.map(key -> new DiaryViewItemDTO(
                DiaryViewItemDTO.Kind.valueOf(key.kind()),
                "PERSISTED_ENTRY".equals(key.kind()) ? key.targetId() : null,
                "DERIVED_COMPLETION".equals(key.kind())
                        ? key.contentType() + ":" + key.seriesTmdbId() + ":" + key.seasonNumber()
                        : null,
                contentById.get(key.contentId()),
                key.watchNumber(),
                key.anchorEntryId(),
                key.watchedDate(),
                key.orderedAt(),
                null,
                null,
                java.util.List.of(),
                0L));
    }

    private void validate(DiaryViewFilter filter) {
        if (filter == null) {
            throw new BadRequestException("Diary view filter is required");
        }
        if (filter.scoreMin() != null && (filter.scoreMin() < 1 || filter.scoreMin() > 10)
                || filter.scoreMax() != null && (filter.scoreMax() < 1 || filter.scoreMax() > 10)) {
            throw new BadRequestException("scoreMin and scoreMax must be between 1 and 10");
        }
        if (filter.scoreMin() != null && filter.scoreMax() != null
                && filter.scoreMin() > filter.scoreMax()) {
            throw new BadRequestException("scoreMin cannot be greater than scoreMax");
        }
        if (filter.dateStart() != null && filter.dateEnd() != null
                && filter.dateStart().isAfter(filter.dateEnd())) {
            throw new BadRequestException("dateStart cannot be after dateEnd");
        }
    }
}
