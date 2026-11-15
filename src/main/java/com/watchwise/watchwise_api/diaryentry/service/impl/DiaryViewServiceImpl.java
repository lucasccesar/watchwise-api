package com.watchwise.watchwise_api.diaryentry.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.common.dto.CursorPageResponseDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardFieldSet;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
import com.watchwise.watchwise_api.content.service.ContentCardSpec;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.impl.ContentCardAssembler;
import com.watchwise.watchwise_api.contentrating.entity.ContentRating;
import com.watchwise.watchwise_api.contentrating.entity.ContentRatingPass;
import com.watchwise.watchwise_api.contentrating.repository.ContentRatingRepository;
import com.watchwise.watchwise_api.contentrating.repository.ContentRatingPassRepository;
import com.watchwise.watchwise_api.contentreview.entity.ContentReview;
import com.watchwise.watchwise_api.contentreview.repository.ContentReviewRepository;
import com.watchwise.watchwise_api.diarycompletion.service.DiaryCompletionService;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryViewFilter;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryViewItemDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryViewResponseItemDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryViewGroupedItemDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryViewReviewPreviewDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryViewSeasonEpisodeCountDTO;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryViewRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.user.service.UserVisibilityService;
import com.watchwise.watchwise_api.userblock.service.UserBlockAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Objects;
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
    private final ContentCardAssembler contentCardAssembler;
    private final ContentRatingRepository contentRatingRepository;
    private final ContentRatingPassRepository contentRatingPassRepository;
    private final ContentReviewRepository contentReviewRepository;
    private final PageRequestFactory pageRequestFactory;

    private static final int GROUPED_VIEW_MAX_PAGE_SIZE = 30;
    private static final long GROUPED_VIEW_ITEM_BUDGET = 300;

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
        refreshCompletions(ownerId, filter);
        Page<DiaryViewRepository.DiaryViewKey> keys = diaryViewRepository.findPage(
                ownerId,
                owner.getPreferredRegion(),
                owner.getPreferredLanguage(),
                java.time.Instant.now(),
                filter,
                pageRequest);
        return enrich(ownerId, keys);
    }

    @Override
    public CursorPageResponseDTO<DiaryViewResponseItemDTO> getGroupedView(UUID viewerId, UUID ownerId,
            DiaryViewFilter filter, String cursor, Integer pageSize) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        userBlockAccessService.assertContentVisible(viewerId, ownerId);
        userVisibilityService.assertCanView(viewerId, ownerId);
        validate(filter);

        int effectiveSize = pageRequestFactory.build(0, pageSize, GROUPED_VIEW_MAX_PAGE_SIZE).getPageSize();
        var cursorPosition = DiaryViewCursorCodec.decode(cursor, ownerId, filter);
        if (cursorPosition == null) {
            refreshCompletions(ownerId, filter);
        }
        List<DiaryViewRepository.GroupedDiaryViewKey> candidates = diaryViewRepository.findGroupedCandidates(
                ownerId, owner.getPreferredRegion(), owner.getPreferredLanguage(), java.time.Instant.now(),
                filter, cursorPosition, effectiveSize + 1);
        List<DiaryViewRepository.GroupedDiaryViewKey> selected = selectGroupedCandidates(candidates, effectiveSize);
        boolean hasNext = candidates.size() > selected.size();
        String nextCursor = hasNext && !selected.isEmpty()
                ? DiaryViewCursorCodec.encode(ownerId, filter, selected.getLast().nextPosition())
                : null;
        List<DiaryViewResponseItemDTO> content = enrichGroupedRows(ownerId, owner, selected);
        return new CursorPageResponseDTO<>(content, effectiveSize, nextCursor, hasNext);
    }

    @Override
    public Page<DiaryViewResponseItemDTO> getView(UUID viewerId, UUID ownerId, DiaryViewFilter filter,
            Integer pageNumber, Integer pageSize, boolean grouped) {
        if (!grouped || (filter != null && Boolean.TRUE.equals(filter.hasReview()))) {
            return getView(viewerId, ownerId, filter, pageNumber, pageSize).map(item -> item);
        }
        User owner = userRepository.findById(ownerId).orElseThrow(() -> new NotFoundException("User not found"));
        userBlockAccessService.assertContentVisible(viewerId, ownerId);
        userVisibilityService.assertCanView(viewerId, ownerId);
        validate(filter);
        PageRequest pageRequest = pageRequestFactory.build(pageNumber, pageSize);
        refreshCompletions(ownerId, filter);
        var rows = diaryViewRepository.findGroupedPage(ownerId, owner.getPreferredRegion(),
                owner.getPreferredLanguage(), java.time.Instant.now(), filter, pageRequest);
        return new org.springframework.data.domain.PageImpl<>(
                enrichGroupedRows(ownerId, owner, rows.getContent()), rows.getPageable(), rows.getTotalElements());
    }

    private List<DiaryViewRepository.GroupedDiaryViewKey> selectGroupedCandidates(
            List<DiaryViewRepository.GroupedDiaryViewKey> candidates, int pageSize) {
        List<DiaryViewRepository.GroupedDiaryViewKey> selected = new ArrayList<>();
        long representedItemCount = 0;
        for (var candidate : candidates) {
            if (selected.size() == pageSize) {
                break;
            }
            long candidateItemCount = candidate.representedItemCount();
            if (!selected.isEmpty() && representedItemCount + candidateItemCount > GROUPED_VIEW_ITEM_BUDGET) {
                break;
            }
            selected.add(candidate);
            representedItemCount += candidateItemCount;
            if (candidateItemCount > GROUPED_VIEW_ITEM_BUDGET || representedItemCount >= GROUPED_VIEW_ITEM_BUDGET) {
                break;
            }
        }
        return List.copyOf(selected);
    }

    private List<DiaryViewResponseItemDTO> enrichGroupedRows(UUID ownerId, User owner,
            List<DiaryViewRepository.GroupedDiaryViewKey> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        var singletons = rows.stream().filter(row -> row.groupSize() == 1)
                .map(DiaryViewRepository.GroupedDiaryViewKey::item).toList();
        var individualItems = enrich(ownerId, new org.springframework.data.domain.PageImpl<>(singletons))
                .getContent().stream().collect(Collectors.toMap(
                        item -> item.diaryEntryId() != null ? item.diaryEntryId().toString() : item.completionKey(),
                        Function.identity()));
        var seriesRefs = contentRepository.findAllById(rows.stream()
                        .filter(row -> row.groupSize() > 1).map(DiaryViewRepository.GroupedDiaryViewKey::seriesContentId)
                        .filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(Content::getId, contentMapper::contentToContentRefDto));
        Map<String, String> backdropPaths = resolveGroupedBackdrops(rows, owner);
        return rows.stream().<DiaryViewResponseItemDTO>map(row -> {
            var key = row.item();
            if (row.groupSize() == 1) {
                var individual = individualItems.get("PERSISTED_ENTRY".equals(key.kind())
                        ? key.targetId().toString() : completionKey(key));
                return individual == null ? null : individual.withRelatedDiaryEntryIds(row.memberEntryIds());
            }
            ContentRefDTO series = row.seriesContentId() == null ? null : seriesRefs.get(row.seriesContentId());
            if (series == null) {
                series = new ContentRefDTO(null, row.seriesTmdbId(), ContentType.SERIES,
                        null, null, null, null, null, null, null);
            }
            return new DiaryViewGroupedItemDTO("GROUPED", series, row.ratingContentId(),
                    backdropPaths.get(row.seriesTmdbId()),
                    row.groupType(), row.seasonCount(), row.seasonNumbers(),
                    row.episodeCount(), row.totalRuntimeMinutes(), row.groupDate(), key.watchNumber(), key.orderedAt(),
                    row.seasonEpisodeCounts().stream()
                            .map(count -> new DiaryViewSeasonEpisodeCountDTO(count.seasonNumber(), count.episodeCount()))
                            .toList(), row.memberEntryIds());
        }).toList();
    }

    private void refreshCompletions(UUID ownerId, DiaryViewFilter filter) {
        java.time.Instant now = java.time.Instant.now();
        diaryCompletionService.refreshDiaryViewScopes(ownerId, now, filter.type() != ContentType.SEASON);
    }

    private Map<String, String> resolveGroupedBackdrops(
            List<DiaryViewRepository.GroupedDiaryViewKey> rows, User owner) {
        List<String> seriesTmdbIds = rows.stream()
                .filter(row -> row.groupSize() > 1)
                .map(DiaryViewRepository.GroupedDiaryViewKey::seriesTmdbId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (seriesTmdbIds.isEmpty()) {
            return Map.of();
        }
        List<ContentCardSpec> specs = seriesTmdbIds.stream()
                .map(seriesTmdbId -> new ContentCardSpec(
                        new ContentCoordinate(ContentType.SERIES, seriesTmdbId, null, null, null),
                        null, null, null, null))
                .toList();
        Map<ContentCoordinate, ContentCardDTO> cards = contentCardAssembler.assemble(
                specs,
                new ContentCardContext(owner.getPreferredLanguage(), owner.getPreferredRegion(), null, null),
                ContentCardFieldSet.BASIC_METADATA);
        Map<String, String> backdropPaths = new LinkedHashMap<>();
        cards.forEach((coordinate, card) -> backdropPaths.put(
                coordinate.tmdbId(), card == null ? null : card.backdropPath()));
        return backdropPaths;
    }

    private Page<DiaryViewItemDTO> enrich(UUID ownerId, Page<DiaryViewRepository.DiaryViewKey> keys) {
        if (keys.isEmpty()) {
            return new org.springframework.data.domain.PageImpl<>(List.of(), keys.getPageable(), keys.getTotalElements());
        }
        Map<UUID, ContentRefDTO> contentById = contentRepository.findAllById(
                        keys.getContent().stream().map(DiaryViewRepository.DiaryViewKey::contentId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Content::getId, contentMapper::contentToContentRefDto));
        List<UUID> contentIds = keys.getContent().stream()
                .map(DiaryViewRepository.DiaryViewKey::contentId)
                .distinct()
                .toList();
        Map<UUID, ContentRating> ratingsByContentId = contentRatingRepository
                .findByUserIdAndContentIdIn(ownerId, contentIds).stream()
                .collect(Collectors.toMap(ContentRating::getContentId, rating -> rating));
        List<Integer> watchNumbers = keys.getContent().stream()
                .filter(key -> "DERIVED_COMPLETION".equals(key.kind()))
                .map(DiaryViewRepository.DiaryViewKey::watchNumber)
                .filter(Objects::nonNull)
                .filter(watchNumber -> watchNumber > 0)
                .distinct()
                .toList();
        Map<PassKey, ContentRatingPass> passRatingsByKey = contentIds.isEmpty() || watchNumbers.isEmpty()
                ? Map.of()
                : contentRatingPassRepository.findByUserIdAndContentIdInAndWatchNumberIn(
                                ownerId, contentIds, watchNumbers).stream()
                        .collect(Collectors.toMap(
                                rating -> new PassKey(rating.getContentId(), rating.getWatchNumber()),
                                Function.identity()));
        Map<UUID, List<ContentReview>> reviewsByContentId = contentReviewRepository
                .findByUserIdAndContentIdInOrderByCreatedAtDesc(ownerId, contentIds).stream()
                .collect(Collectors.groupingBy(ContentReview::getContentId));
        return keys.map(key -> {
            List<ContentReview> reviews = reviewsForKey(key, reviewsByContentId);
            ContentRatingPass passRating = "DERIVED_COMPLETION".equals(key.kind())
                    ? passRatingsByKey.get(new PassKey(key.contentId(), key.watchNumber()))
                    : null;
            Integer currentScore = passRating != null
                    ? passRating.getScore()
                    : key.score() != null ? key.score() : ratingScore(ratingsByContentId.get(key.contentId()));
            return new DiaryViewItemDTO(
                DiaryViewItemDTO.Kind.valueOf(key.kind()),
                "PERSISTED_ENTRY".equals(key.kind()) ? key.targetId() : null,
                "DERIVED_COMPLETION".equals(key.kind())
                        ? completionKey(key)
                        : null,
                contentById.get(key.contentId()),
                key.watchNumber(),
                key.anchorEntryId(),
                key.watchedDate(),
                key.orderedAt(),
                currentScore,
                ratingVersion(ratingsByContentId.get(key.contentId())),
                reviews.stream()
                        .limit(3)
                        .map(this::toReviewPreview)
                        .toList(),
                reviews.size(),
                List.of());
        });
    }

    private List<ContentReview> reviewsForKey(
            DiaryViewRepository.DiaryViewKey key,
            Map<UUID, List<ContentReview>> reviewsByContentId) {
        List<ContentReview> reviews = reviewsByContentId.getOrDefault(key.contentId(), List.of());
        if (!"DERIVED_COMPLETION".equals(key.kind())) {
            return reviews;
        }
        return reviews.stream()
                .filter(review -> Objects.equals(review.getWatchNumber(), key.watchNumber()))
                .filter(review -> review.getComment() != null && !review.getComment().isBlank())
                .toList();
    }

    private String completionKey(DiaryViewRepository.DiaryViewKey key) {
        String contentId = key.contentType() == ContentType.SERIES
                ? key.tmdbId()
                : key.seriesTmdbId();
        String base = key.contentType() + ":" + contentId;
        if (key.seasonNumber() != null) {
            base += ":" + key.seasonNumber();
        }
        return base + ":pass:" + key.watchNumber();
    }

    private Integer ratingScore(ContentRating rating) {
        return rating == null ? null : rating.getScore();
    }

    private Long ratingVersion(ContentRating rating) {
        return rating == null ? null : rating.getVersion();
    }

    private record PassKey(UUID contentId, Integer watchNumber) { }

    private DiaryViewReviewPreviewDTO toReviewPreview(ContentReview review) {
        return new DiaryViewReviewPreviewDTO(
                review.getId(),
                review.getPublicationKind(),
                review.getContextKind(),
                review.getComment(),
                review.getScoreAtPublication(),
                review.getWatchNumber(),
                review.getSourcePublishedAt(),
                review.getSourcePublishedOn(),
                review.getCreatedAt());
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
        if (filter.seriesTmdbId() != null && filter.seriesTmdbId().isBlank()) {
            throw new BadRequestException("seriesTmdbId cannot be blank");
        }
    }
}
