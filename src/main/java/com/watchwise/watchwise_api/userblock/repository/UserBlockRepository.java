package com.watchwise.watchwise_api.userblock.repository;

import com.watchwise.watchwise_api.userblock.entity.UserBlock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface UserBlockRepository extends JpaRepository<UserBlock, UUID> {

    interface PairDirection {
        UUID getBlockerId();
        UUID getBlockedId();
    }

    @Query("""
            SELECT b.blocker.id AS blockerId, b.blocked.id AS blockedId
            FROM UserBlock b
            WHERE (b.blocker.id = :firstUserId AND b.blocked.id = :secondUserId)
               OR (b.blocker.id = :secondUserId AND b.blocked.id = :firstUserId)
            """)
    List<PairDirection> findPairDirections(@Param("firstUserId") UUID firstUserId,
            @Param("secondUserId") UUID secondUserId);

    @Query("""
            SELECT CASE WHEN COUNT(b) > 0 THEN true ELSE false END
            FROM UserBlock b
            WHERE (b.blocker.id = :firstUserId AND b.blocked.id = :secondUserId)
               OR (b.blocker.id = :secondUserId AND b.blocked.id = :firstUserId)
            """)
    boolean existsEitherDirection(@Param("firstUserId") UUID firstUserId,
            @Param("secondUserId") UUID secondUserId);

    boolean existsByBlockerIdAndBlockedId(UUID blockerId, UUID blockedId);

    Optional<UserBlock> findByBlockerIdAndBlockedId(UUID blockerId, UUID blockedId);

    @Query("""
            SELECT b FROM UserBlock b JOIN FETCH b.blocked
            WHERE b.blocker.id = :blockerId
            ORDER BY b.createdAt DESC, b.id DESC
            """)
    Page<UserBlock> findByBlockerIdOrderByCreatedAtDescIdDesc(
            @Param("blockerId") UUID blockerId, Pageable pageable);
}
