package com.watchwise.watchwise_api.followedperson.repository;

import com.watchwise.watchwise_api.followedperson.entity.FollowedPerson;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FollowedPersonRepository extends JpaRepository<FollowedPerson, UUID> {

    Optional<FollowedPerson> findByUserIdAndPersonTmdbId(UUID userId, String personTmdbId);

    boolean existsByUserIdAndPersonTmdbId(UUID userId, String personTmdbId);

    Page<FollowedPerson> findByUserId(UUID userId, Pageable pageable);

}