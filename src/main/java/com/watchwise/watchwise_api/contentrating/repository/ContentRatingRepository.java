package com.watchwise.watchwise_api.contentrating.repository;

import com.watchwise.watchwise_api.contentrating.entity.ContentRating;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface ContentRatingRepository extends JpaRepository<ContentRating, UUID> {
    Optional<ContentRating> findByUserIdAndContentId(UUID userId, UUID contentId);
}
