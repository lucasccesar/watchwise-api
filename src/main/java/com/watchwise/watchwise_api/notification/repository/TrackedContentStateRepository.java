package com.watchwise.watchwise_api.notification.repository;

import com.watchwise.watchwise_api.notification.entity.TrackedContentState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface TrackedContentStateRepository extends JpaRepository<TrackedContentState, UUID> {

    Optional<TrackedContentState> findByContentId(UUID contentId);

    @Query("SELECT t.lastKnownStatus FROM TrackedContentState t WHERE t.content.id = :contentId")
    Optional<String> findLastKnownStatusByContentId(@Param("contentId") UUID contentId);

}
