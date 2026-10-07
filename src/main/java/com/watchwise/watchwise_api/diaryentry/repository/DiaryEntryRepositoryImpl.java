package com.watchwise.watchwise_api.diaryentry.repository;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Repository
public class DiaryEntryRepositoryImpl implements DiaryEntryRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<DiaryEntryRepository.WatchedEpisodeCoordinateProjection>
    findWatchedEpisodeCoordinatesByUserIdAndSeriesIdsOrSeriesSeasonPairs(
            UUID userId,
            Collection<String> seriesTmdbIds,
            Collection<DiaryEntryRepositoryCustom.SeriesSeasonPair> seriesSeasonPairs) {
        List<String> requestedSeriesIds = seriesTmdbIds == null
                ? List.of()
                : seriesTmdbIds.stream()
                        .filter(this::hasText)
                        .distinct()
                        .toList();
        List<DiaryEntryRepositoryCustom.SeriesSeasonPair> requestedSeriesSeasonPairs = seriesSeasonPairs == null
                ? List.of()
                : seriesSeasonPairs.stream()
                        .filter(Objects::nonNull)
                        .filter(pair -> hasText(pair.seriesTmdbId()) && pair.seasonNumber() != null)
                        .distinct()
                        .toList();
        if (userId == null || requestedSeriesIds.isEmpty() && requestedSeriesSeasonPairs.isEmpty()) {
            return List.of();
        }

        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = builder.createTupleQuery();
        Root<DiaryEntry> diaryEntry = query.from(DiaryEntry.class);
        Join<DiaryEntry, ?> content = diaryEntry.join("content", JoinType.INNER);
        var seriesTmdbId = content.<String>get("seriesTmdbId");
        var seasonNumber = content.<Integer>get("seasonNumber");
        var episodeNumber = content.<Integer>get("episodeNumber");

        List<Predicate> scopePredicates = new java.util.ArrayList<>();
        if (!requestedSeriesIds.isEmpty()) {
            scopePredicates.add(seriesTmdbId.in(new LinkedHashSet<>(requestedSeriesIds)));
        }
        for (DiaryEntryRepositoryCustom.SeriesSeasonPair pair : requestedSeriesSeasonPairs) {
            scopePredicates.add(builder.and(
                    builder.equal(seriesTmdbId, pair.seriesTmdbId()),
                    builder.equal(seasonNumber, pair.seasonNumber())));
        }

        query.multiselect(
                        seriesTmdbId.alias("seriesTmdbId"),
                        seasonNumber.alias("seasonNumber"),
                        episodeNumber.alias("episodeNumber"))
                .distinct(true)
                .where(builder.and(
                        builder.equal(diaryEntry.get("user").get("id"), userId),
                        builder.equal(content.get("type"), ContentType.EPISODE),
                        builder.or(scopePredicates.toArray(Predicate[]::new))));

        return entityManager.createQuery(query).getResultList().stream()
                .map(this::toProjection)
                .toList();
    }

    private DiaryEntryRepository.WatchedEpisodeCoordinateProjection toProjection(Tuple row) {
        return new DiaryEntryRepository.WatchedEpisodeCoordinateRow(
                row.get("seriesTmdbId", String.class),
                row.get("seasonNumber", Integer.class),
                row.get("episodeNumber", Integer.class));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
