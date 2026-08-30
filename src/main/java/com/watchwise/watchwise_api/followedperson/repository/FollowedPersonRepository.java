package com.watchwise.watchwise_api.followedperson.repository;

import com.watchwise.watchwise_api.followedperson.entity.FollowedPerson;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FollowedPersonRepository extends JpaRepository<FollowedPerson, UUID> {

    Optional<FollowedPerson> findByUserIdAndPersonTmdbId(UUID userId, String personTmdbId);

    boolean existsByUserIdAndPersonTmdbId(UUID userId, String personTmdbId);

    Page<FollowedPerson> findByUserId(UUID userId, Pageable pageable);

    @Query("SELECT DISTINCT f.personTmdbId FROM FollowedPerson f")
    List<String> findDistinctPersonTmdbIds();

    @Query("SELECT f.user.id FROM FollowedPerson f WHERE f.personTmdbId = :personTmdbId")
    List<UUID> findUserIdsByPersonTmdbId(@Param("personTmdbId") String personTmdbId);

}