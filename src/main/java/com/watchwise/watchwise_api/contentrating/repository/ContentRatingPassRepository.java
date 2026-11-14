package com.watchwise.watchwise_api.contentrating.repository;

import com.watchwise.watchwise_api.contentrating.entity.ContentRatingPass;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContentRatingPassRepository extends JpaRepository<ContentRatingPass, UUID> {

    Optional<ContentRatingPass> findByUserIdAndContentIdAndWatchNumber(
            UUID userId, UUID contentId, Integer watchNumber);

    Optional<ContentRatingPass> findFirstByUserIdAndContentIdAndWatchNumberIsNull(
            UUID userId, UUID contentId);

    Optional<ContentRatingPass> findFirstByUserIdAndContentIdAndWatchNumberLessThanOrderByWatchNumberDesc(
            UUID userId, UUID contentId, Integer watchNumber);

    List<ContentRatingPass> findByUserIdAndContentIdInAndWatchNumberIn(
            UUID userId, Collection<UUID> contentIds, Collection<Integer> watchNumbers);
}
