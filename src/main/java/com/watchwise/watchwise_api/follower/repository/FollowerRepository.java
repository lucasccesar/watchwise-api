package com.watchwise.watchwise_api.follower.repository;

import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.entity.Follower;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FollowerRepository extends JpaRepository<Follower, UUID> {

    @Query("SELECT f.followed.id FROM Follower f WHERE f.follower.id = :followerId AND f.status = :status")
    List<UUID> findFollowedIdsByFollowerIdAndStatus(@Param("followerId") UUID followerId, @Param("status") FollowStatus status);

    Optional<Follower> findByFollowerIdAndFollowedId(UUID followerId, UUID followedId);

    Optional<Follower> findByFollowerIdAndFollowedIdAndStatus(UUID followerId, UUID followedId, FollowStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT f FROM Follower f
            WHERE f.follower.id = :followerId AND f.followed.id = :followedId
              AND f.status = com.watchwise.watchwise_api.follower.entity.FollowStatus.PENDING
            """)
    Optional<Follower> findPendingByFollowerIdAndFollowedIdForUpdate(
            @Param("followerId") UUID followerId, @Param("followedId") UUID followedId);

    boolean existsByFollowerIdAndFollowedIdAndStatus(UUID followerId, UUID followedId, FollowStatus status);

    long countByFollowedIdAndStatus(UUID followedId, FollowStatus status);

    long countByFollowerIdAndStatus(UUID followerId, FollowStatus status);

    @Query("""
            SELECT f FROM Follower f JOIN FETCH f.follower
            WHERE f.followed.id = :followedId AND f.status = :status
            ORDER BY f.createdAt DESC, f.id DESC
            """)
    Page<Follower> findByFollowedIdAndStatus(
            @Param("followedId") UUID followedId, @Param("status") FollowStatus status, Pageable pageable);

    @Query("""
            SELECT f FROM Follower f JOIN FETCH f.followed
            WHERE f.follower.id = :followerId AND f.status = :status
            ORDER BY f.createdAt DESC, f.id DESC
            """)
    Page<Follower> findByFollowerIdAndStatus(
            @Param("followerId") UUID followerId, @Param("status") FollowStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT f FROM Follower f JOIN FETCH f.follower
            WHERE f.followed.id = :followedId
              AND f.status = com.watchwise.watchwise_api.follower.entity.FollowStatus.PENDING
            """)
    List<Follower> findPendingByFollowedIdForUpdate(@Param("followedId") UUID followedId);

}
