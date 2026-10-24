package com.watchwise.watchwise_api.summary.service.impl;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.contentrating.entity.ContentRating;
import com.watchwise.watchwise_api.contentrating.repository.ContentRatingRepository;
import com.watchwise.watchwise_api.summary.dto.ContentRatingSummaryDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CurrentRatingSummaryReader {

    private static final List<ContentType> SERIES_OPINION_TYPES = List.of(ContentType.SERIES, ContentType.SEASON);

    private final ContentRatingRepository contentRatingRepository;
    private final ContentRepository contentRepository;
    private final ContentMapper contentMapper;

    @Transactional(readOnly = true)
    public Ratings readSeriesRatings(UUID userId, int limit) {
        if (userId == null || limit <= 0) {
            return Ratings.empty();
        }
        PageRequest page = PageRequest.of(0, limit);
        List<ContentRating> top = contentRatingRepository
                .findByUserIdAndContentTypeInOrderByScoreDescContentIdAsc(userId, SERIES_OPINION_TYPES, page);
        List<ContentRating> bottom = contentRatingRepository
                .findByUserIdAndContentTypeInOrderByScoreAscContentIdAsc(userId, SERIES_OPINION_TYPES, page);
        Map<UUID, Content> contentById = loadContent(top, bottom);
        return new Ratings(toDtos(top, contentById), toDtos(bottom, contentById));
    }

    @SafeVarargs
    private final Map<UUID, Content> loadContent(Collection<ContentRating>... groups) {
        List<UUID> contentIds = java.util.Arrays.stream(groups)
                .flatMap(Collection::stream)
                .map(ContentRating::getContentId)
                .distinct()
                .toList();
        if (contentIds.isEmpty()) {
            return Map.of();
        }
        return contentRepository.findAllById(contentIds).stream()
                .collect(Collectors.toMap(Content::getId, content -> content, (first, ignored) -> first,
                        LinkedHashMap::new));
    }

    private List<ContentRatingSummaryDTO> toDtos(List<ContentRating> ratings, Map<UUID, Content> contentById) {
        return ratings.stream()
                .map(rating -> toDto(rating, contentById.get(rating.getContentId())))
                .filter(dto -> dto != null)
                .toList();
    }

    private ContentRatingSummaryDTO toDto(ContentRating rating, Content content) {
        if (content == null) {
            return null;
        }
        ContentRefDTO reference = contentMapper.contentToContentRefDto(content);
        return new ContentRatingSummaryDTO(reference, rating.getScore(), rating.getVersion());
    }

    public record Ratings(List<ContentRatingSummaryDTO> top, List<ContentRatingSummaryDTO> bottom) {
        public Ratings {
            top = top == null ? List.of() : List.copyOf(top);
            bottom = bottom == null ? List.of() : List.copyOf(bottom);
        }

        public static Ratings empty() {
            return new Ratings(List.of(), List.of());
        }
    }
}
