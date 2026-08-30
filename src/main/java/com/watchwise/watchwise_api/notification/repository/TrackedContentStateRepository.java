package com.watchwise.watchwise_api.notification.repository;

import com.watchwise.watchwise_api.notification.entity.TrackedContentState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TrackedContentStateRepository extends JpaRepository<TrackedContentState, UUID> {

    Optional<TrackedContentState> findByContentId(UUID contentId);

}
