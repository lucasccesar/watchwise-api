package com.watchwise.watchwise_api.contentreview.service;

import com.watchwise.watchwise_api.contentreview.entity.ContentReview;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

@Component
public final class ReviewPublicationOrder {

    public enum Order {
        NEWER,
        OLDER,
        AMBIGUOUS
    }

    public Order compare(ContentReview candidate, List<ContentReview> others) {
        Objects.requireNonNull(candidate, "candidate is required");
        if (candidate.getScoreAtPublication() == null) {
            return Order.OLDER;
        }
        if (others == null || others.isEmpty()) {
            return Order.NEWER;
        }

        boolean ambiguous = false;
        for (ContentReview other : others) {
            if (other == null || other.getScoreAtPublication() == null) {
                continue;
            }
            Order pairOrder = comparePair(candidate, other);
            if (pairOrder == Order.OLDER) {
                return Order.OLDER;
            }
            ambiguous |= pairOrder == Order.AMBIGUOUS;
        }
        return ambiguous ? Order.AMBIGUOUS : Order.NEWER;
    }

    public boolean isNewLatest(ContentReview candidate, List<ContentReview> others) {
        return compare(candidate, others) == Order.NEWER;
    }

    private Order comparePair(ContentReview candidate, ContentReview other) {
        Instant candidateInstant = candidate.getSourcePublishedAt();
        Instant otherInstant = other.getSourcePublishedAt();
        LocalDate candidateDate = candidate.getSourcePublishedOn();
        LocalDate otherDate = other.getSourcePublishedOn();

        if (candidateInstant != null && otherInstant != null) {
            return compareValues(candidateInstant.compareTo(otherInstant));
        }
        if (candidateInstant == null && otherInstant == null
                && candidateDate != null && otherDate != null) {
            return compareValues(candidateDate.compareTo(otherDate));
        }
        if (candidateInstant != null && otherInstant == null && otherDate != null) {
            return compareValues(LocalDate.ofInstant(candidateInstant, ZoneOffset.UTC).compareTo(otherDate));
        }
        if (candidateInstant == null && candidateDate != null && otherInstant != null) {
            return compareValues(candidateDate.compareTo(LocalDate.ofInstant(otherInstant, ZoneOffset.UTC)));
        }
        return Order.AMBIGUOUS;
    }

    private Order compareValues(int comparison) {
        if (comparison > 0) {
            return Order.NEWER;
        }
        if (comparison < 0) {
            return Order.OLDER;
        }
        return Order.AMBIGUOUS;
    }
}
