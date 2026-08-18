package com.watchwise.watchwise_api.follower.repository;

import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.entity.Follower;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface FollowerRepository extends JpaRepository<Follower, UUID> {

    Optional<Follower> findByFollowerIdAndFollowedId(UUID followerId, UUID followedId);

    Optional<Follower> findByFollowerIdAndFollowedIdAndStatus(UUID followerId, UUID followedId, FollowStatus status);

    boolean existsByFollowerIdAndFollowedIdAndStatus(UUID followerId, UUID followedId, FollowStatus status);

    Page<Follower> findByFollowedIdAndStatus(UUID followedId, FollowStatus status, Pageable pageable);

    Page<Follower> findByFollowerIdAndStatus(UUID followerId, FollowStatus status, Pageable pageable);

    @Transactional
    @Modifying
    @Query("update Follower f set f.status = com.watchwise.watchwise_api.follower.entity.FollowStatus.ACCEPTED "
            + "where f.followed.id = :followedId and f.status = com.watchwise.watchwise_api.follower.entity.FollowStatus.PENDING")
    void acceptAllPendingFollowRequestsFor(@Param("followedId") UUID followedId);

}