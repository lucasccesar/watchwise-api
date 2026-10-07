package com.watchwise.watchwise_api.content.repository;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

@Repository
public class ContentRepositoryImpl implements ContentRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<Content> findAllByCoordinates(Collection<ContentCoordinate> coordinates) {
        List<ContentCoordinate> requestedCoordinates = coordinates == null
                ? List.of()
                : coordinates.stream()
                        .filter(Objects::nonNull)
                        .filter(this::isQueryable)
                        .distinct()
                        .toList();
        if (requestedCoordinates.isEmpty()) {
            return List.of();
        }

        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Content> query = builder.createQuery(Content.class);
        Root<Content> content = query.from(Content.class);
        List<Predicate> coordinatePredicates = requestedCoordinates.stream()
                .map(coordinate -> coordinatePredicate(builder, content, coordinate))
                .toList();

        query.select(content).where(builder.or(coordinatePredicates.toArray(Predicate[]::new)));
        return entityManager.createQuery(query).getResultList();
    }

    private Predicate coordinatePredicate(
            CriteriaBuilder builder, Root<Content> content, ContentCoordinate coordinate) {
        Predicate type = builder.equal(content.get("type"), coordinate.type());
        return switch (coordinate.type()) {
            case MOVIE, SERIES -> builder.and(type,
                    builder.equal(content.get("tmdbId"), coordinate.tmdbId()));
            case SEASON -> builder.and(type,
                    builder.equal(content.get("seriesTmdbId"), coordinate.seriesTmdbId()),
                    builder.equal(content.get("seasonNumber"), coordinate.seasonNumber()));
            case EPISODE -> builder.and(type,
                    builder.equal(content.get("seriesTmdbId"), coordinate.seriesTmdbId()),
                    builder.equal(content.get("seasonNumber"), coordinate.seasonNumber()),
                    builder.equal(content.get("episodeNumber"), coordinate.episodeNumber()));
        };
    }

    private boolean isQueryable(ContentCoordinate coordinate) {
        if (coordinate.type() == null) {
            return false;
        }
        return switch (coordinate.type()) {
            case MOVIE, SERIES -> hasText(coordinate.tmdbId());
            case SEASON -> hasText(coordinate.seriesTmdbId()) && coordinate.seasonNumber() != null;
            case EPISODE -> hasText(coordinate.seriesTmdbId())
                    && coordinate.seasonNumber() != null
                    && coordinate.episodeNumber() != null;
        };
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
