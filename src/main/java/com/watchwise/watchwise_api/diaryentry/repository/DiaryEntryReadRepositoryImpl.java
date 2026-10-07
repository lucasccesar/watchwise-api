package com.watchwise.watchwise_api.diaryentry.repository;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Repository
public class DiaryEntryReadRepositoryImpl implements DiaryEntryReadRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<DiaryEntry> findPage(
            DiaryEntrySearchCriteria criteria, DiaryEntrySort sort, Pageable pageable) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();

        CriteriaQuery<DiaryEntry> query = builder.createQuery(DiaryEntry.class);
        Root<DiaryEntry> root = query.from(DiaryEntry.class);
        root.fetch("content", JoinType.INNER);
        root.fetch("user", JoinType.INNER);
        Join<DiaryEntry, ?> content = root.join("content", JoinType.INNER);
        query.select(root).where(predicates(builder, root, content, criteria));
        query.orderBy(orderBy(builder, root, sort));

        TypedQuery<DiaryEntry> typedQuery = entityManager.createQuery(query)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize());

        return new PageImpl<>(typedQuery.getResultList(), pageable, count(criteria));
    }

    private long count(DiaryEntrySearchCriteria criteria) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = builder.createQuery(Long.class);
        Root<DiaryEntry> root = query.from(DiaryEntry.class);
        Join<DiaryEntry, ?> content = root.join("content", JoinType.INNER);
        query.select(builder.count(root)).where(predicates(builder, root, content, criteria));
        return entityManager.createQuery(query).getSingleResult();
    }

    private List<Predicate> predicates(
            CriteriaBuilder builder,
            Root<DiaryEntry> root,
            Join<DiaryEntry, ?> content,
            DiaryEntrySearchCriteria criteria) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(builder.equal(root.get("user").get("id"), criteria.userId()));

        if (criteria.type() != null) {
            predicates.add(builder.equal(content.get("type"), criteria.type()));
        }
        if (criteria.dateFrom() != null) {
            predicates.add(builder.greaterThanOrEqualTo(root.get("watchedDate"), criteria.dateFrom()));
        }
        if (criteria.dateTo() != null) {
            predicates.add(builder.lessThanOrEqualTo(root.get("watchedDate"), criteria.dateTo()));
        }
        if (criteria.hasReview() != null) {
            predicates.add(Boolean.TRUE.equals(criteria.hasReview())
                    ? builder.isNotNull(root.get("comment"))
                    : builder.isNull(root.get("comment")));
        }
        if (criteria.seriesTmdbId() != null) {
            predicates.add(builder.or(
                    builder.and(
                            builder.equal(content.get("type"), ContentType.SERIES),
                            builder.equal(content.get("tmdbId"), criteria.seriesTmdbId())),
                    builder.and(
                            content.get("type").in(ContentType.SEASON, ContentType.EPISODE),
                            builder.equal(content.get("seriesTmdbId"), criteria.seriesTmdbId()))));
        }
        if (criteria.score() != null) {
            predicates.add(builder.equal(root.get("score"), criteria.score()));
        }
        if (criteria.scoreFrom() != null) {
            predicates.add(builder.greaterThanOrEqualTo(root.get("score"), criteria.scoreFrom()));
        }
        if (criteria.scoreTo() != null) {
            predicates.add(builder.lessThanOrEqualTo(root.get("score"), criteria.scoreTo()));
        }
        return predicates;
    }

    private List<jakarta.persistence.criteria.Order> orderBy(
            CriteriaBuilder builder, Root<DiaryEntry> root, DiaryEntrySort sort) {
        Expression<LocalDateTime> createdAt = root.get("createdAt");
        Expression<?> id = root.get("id");

        return switch (sort) {
            case OLDEST -> List.of(builder.asc(createdAt), builder.asc(id));
            case RATING_DESC -> ratingOrder(builder, root, true, createdAt, id);
            case RATING_ASC -> ratingOrder(builder, root, false, createdAt, id);
            case NEWEST -> List.of(builder.desc(createdAt), builder.desc(id));
        };
    }

    private List<jakarta.persistence.criteria.Order> ratingOrder(
            CriteriaBuilder builder,
            Root<DiaryEntry> root,
            boolean descending,
            Expression<LocalDateTime> createdAt,
            Expression<?> id) {
        Expression<Integer> nullScore = builder.<Integer>selectCase()
                .when(builder.isNull(root.get("score")), 1)
                .otherwise(0);
        jakarta.persistence.criteria.Order scoreOrder = descending
                ? builder.desc(root.get("score"))
                : builder.asc(root.get("score"));
        return List.of(builder.asc(nullScore), scoreOrder, builder.desc(createdAt), builder.desc(id));
    }
}
