package com.watchwise.watchwise_api.search.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardFieldSet;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
import com.watchwise.watchwise_api.content.service.ContentCardSpec;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.impl.ContentCardAssembler;
import com.watchwise.watchwise_api.search.dto.SearchContentViewDTO;
import com.watchwise.watchwise_api.user.entity.User;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public class SearchContentViewAssembler {

    private static final Set<ContentCardFieldSet> CARD_FIELDS = Set.of(
            ContentCardFieldSet.BASIC_METADATA,
            ContentCardFieldSet.VIEWER_STATE,
            ContentCardFieldSet.WATCHLIST_PROGRESS);

    private final ContentCardAssembler contentCardAssembler;

    public SearchContentViewAssembler(ContentCardAssembler contentCardAssembler) {
        this.contentCardAssembler = contentCardAssembler;
    }

    public List<SearchContentViewDTO> assemble(User viewer, List<SearchContentCandidate> candidates) {
        Objects.requireNonNull(viewer, "viewer is required");
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<SearchContentCandidate> orderedCandidates = candidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> candidate.coordinate() != null)
                .toList();
        if (orderedCandidates.isEmpty()) {
            return List.of();
        }

        List<ContentCardSpec> specs = distinctSpecs(orderedCandidates);
        Map<ContentCoordinate, ContentCardDTO> cardsByCoordinate = specs.isEmpty()
                ? Map.of()
                : safeCards(contentCardAssembler.assemble(
                        specs,
                        new ContentCardContext(
                                viewer.getPreferredLanguage(), viewer.getPreferredRegion(), null, viewer.getId()),
                        CARD_FIELDS));

        return orderedCandidates.stream()
                .map(candidate -> toView(candidate, cardsByCoordinate.get(candidate.coordinate())))
                .toList();
    }

    private Map<ContentCoordinate, ContentCardDTO> safeCards(
            Map<ContentCoordinate, ContentCardDTO> cardsByCoordinate) {
        return cardsByCoordinate == null ? Map.of() : cardsByCoordinate;
    }

    private List<ContentCardSpec> distinctSpecs(List<SearchContentCandidate> candidates) {
        Map<ContentCoordinate, ContentCardSpec> specsByCoordinate = new LinkedHashMap<>();
        for (SearchContentCandidate candidate : candidates) {
            ContentCoordinate coordinate = candidate.coordinate();
            specsByCoordinate.putIfAbsent(coordinate, new ContentCardSpec(
                    coordinate,
                    candidate.title(),
                    candidate.posterPath(),
                    candidate.releaseDate(),
                    candidate.runtimeMinutes(),
                    candidate.genres()));
        }
        return specsByCoordinate.values().stream().toList();
    }

    private SearchContentViewDTO toView(SearchContentCandidate candidate, ContentCardDTO card) {
        String posterPath = firstNonBlank(card == null ? null : card.posterPath(), candidate.posterPath());
        String title = firstNonBlank(card == null ? null : card.title(), candidate.title());
        Integer releaseYear = firstNonNull(
                card == null ? null : card.releaseYear(),
                releaseYear(candidate.releaseDate()));
        Integer runtimeMinutes = firstNonNull(
                card == null ? null : card.runtimeMinutes(), candidate.runtimeMinutes());
        List<String> genres = firstNonNull(card == null ? null : card.genres(), candidate.genres());
        Integer numberOfSeasons = firstNonNull(
                card == null ? null : card.numberOfSeasons(), candidate.numberOfSeasons());
        ContentPreviewStatus previewStatus = card == null || card.previewStatus() == null
                ? ContentPreviewStatus.PARTIAL
                : card.previewStatus();
        MovieOrSeriesType type = MovieOrSeriesType.valueOf(candidate.coordinate().type().name());

        return new SearchContentViewDTO(
                candidate.coordinate().tmdbId(),
                type,
                title,
                TmdbImageUrlBuilder.posterUrl(posterPath),
                releaseYear,
                genres,
                runtimeMinutes,
                numberOfSeasons,
                candidate.voteAverage(),
                card == null ? null : card.viewerState(),
                previewStatus);
    }

    private Integer releaseYear(LocalDate releaseDate) {
        return releaseDate == null ? null : releaseDate.getYear();
    }

    private <T> T firstNonNull(T primary, T fallback) {
        return primary == null ? fallback : primary;
    }

    private String firstNonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }
}

record SearchContentCandidate(
        ContentCoordinate coordinate,
        String title,
        String posterPath,
        LocalDate releaseDate,
        Integer runtimeMinutes,
        Double voteAverage,
        List<String> genres,
        Integer numberOfSeasons) {

    SearchContentCandidate(
            ContentCoordinate coordinate,
            String title,
            String posterPath,
            LocalDate releaseDate,
            Integer runtimeMinutes,
            Double voteAverage,
            List<String> genres) {
        this(coordinate, title, posterPath, releaseDate, runtimeMinutes, voteAverage, genres, null);
    }

    SearchContentCandidate {
        genres = genres == null ? null : List.copyOf(genres);
    }
}
