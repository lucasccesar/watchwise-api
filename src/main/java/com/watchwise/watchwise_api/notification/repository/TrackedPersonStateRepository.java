package com.watchwise.watchwise_api.notification.repository;

import com.watchwise.watchwise_api.notification.entity.TrackedPersonState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TrackedPersonStateRepository extends JpaRepository<TrackedPersonState, UUID> {

    Optional<TrackedPersonState> findByPersonTmdbId(String personTmdbId);

}
