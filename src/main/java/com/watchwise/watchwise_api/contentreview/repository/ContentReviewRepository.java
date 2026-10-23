package com.watchwise.watchwise_api.contentreview.repository;

import com.watchwise.watchwise_api.contentreview.entity.ContentReview;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ContentReviewRepository extends JpaRepository<ContentReview, UUID> {
    List<ContentReview> findByUserIdAndContentIdOrderByCreatedAtDesc(UUID userId, UUID contentId);
}
