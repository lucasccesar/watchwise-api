package com.watchwise.watchwise_api.pick.repository;

import com.watchwise.watchwise_api.pick.entity.Pick;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PickRepository extends JpaRepository<Pick, UUID> {
    @Query("select pick.picksTemplate.id from Pick pick where pick.id = :id and pick.user.id = :userId")
    Optional<UUID> findOwnedTemplateId(@Param("id") UUID id, @Param("userId") UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select pick from Pick pick where pick.id = :id")
    Optional<Pick> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            select pick from Pick pick
            join fetch pick.user
            join fetch pick.picksTemplate
            where pick.user.id in :followedUserIds
              and (
                  pick.visibility = com.watchwise.watchwise_api.pick.entity.PickVisibility.PUBLIC
                  or (pick.visibility = com.watchwise.watchwise_api.pick.entity.PickVisibility.FOLLOWERS and exists (
                      select follower.id from Follower follower
                      where follower.follower.id = :viewerId
                        and follower.followed.id = pick.user.id
                        and follower.status = com.watchwise.watchwise_api.follower.entity.FollowStatus.ACCEPTED
                  ))
              )
              and (
                  cast(:cursorCreatedAt as timestamp) is null
                  or pick.createdAt < :cursorCreatedAt
                  or (pick.createdAt = :cursorCreatedAt and (:cursorId is null or pick.id < :cursorId))
              )
            order by pick.createdAt desc, pick.id desc
            """)
    List<Pick> findFeedCandidates(
            @Param("followedUserIds") Collection<UUID> followedUserIds,
            @Param("viewerId") UUID viewerId,
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);

    Page<Pick> findByUserIdAndPicksTemplateIdOrderByCreatedAtDescIdDesc(UUID userId, UUID templateId, Pageable pageable);
    List<Pick> findByUserIdAndPicksTemplateIdInOrderByCreatedAtDescIdDesc(UUID userId, Collection<UUID> templateIds);

    @Query("select pick.picksTemplate.id as templateId, count(pick) as count from Pick pick "
            + "where pick.user.id = :userId and pick.picksTemplate.id in :templateIds group by pick.picksTemplate.id")
    List<OwnPickCount> countByUserIdAndTemplateIds(@Param("userId") UUID userId,
            @Param("templateIds") Collection<UUID> templateIds);

    @Query(value = "select ranked.picks_template_id as templateId, ranked.id as pickId "
            + "from (select p.id, p.picks_template_id, row_number() over "
            + "(partition by p.picks_template_id order by p.created_at desc, p.id desc) as row_number "
            + "from picks p where p.user_id = :userId and p.picks_template_id in (:templateIds)) ranked "
            + "where ranked.row_number = 1", nativeQuery = true)
    List<LatestOwnPick> findLatestByUserIdAndTemplateIds(@Param("userId") UUID userId,
            @Param("templateIds") Collection<UUID> templateIds);

    interface OwnPickCount {
        UUID getTemplateId();
        long getCount();
    }

    interface LatestOwnPick {
        UUID getTemplateId();
        UUID getPickId();
    }

    @Query("select pick.picksTemplate.id as templateId, count(pick) as count from Pick pick "
            + "where pick.picksTemplate.id in :templateIds and (pick.user.id = :viewerId "
            + "or pick.visibility = com.watchwise.watchwise_api.pick.entity.PickVisibility.PUBLIC "
            + "or (pick.visibility = com.watchwise.watchwise_api.pick.entity.PickVisibility.FOLLOWERS and exists "
            + "(select 1 from Follower follower where follower.follower.id = :viewerId "
            + "and follower.followed.id = pick.user.id and follower.status = com.watchwise.watchwise_api.follower.entity.FollowStatus.ACCEPTED))) "
            + "group by pick.picksTemplate.id")
    List<VisiblePickCount> countVisibleByTemplateIds(@Param("viewerId") UUID viewerId,
            @Param("templateIds") Collection<UUID> templateIds);

    interface VisiblePickCount {
        UUID getTemplateId();
        long getCount();
    }
    @Query("""
            select pick from Pick pick where pick.user.id = :ownerId
              and (:templateId is null or pick.picksTemplate.id = :templateId)
              and (pick.user.id = :viewerId
                or pick.visibility = com.watchwise.watchwise_api.pick.entity.PickVisibility.PUBLIC
                or (pick.visibility = com.watchwise.watchwise_api.pick.entity.PickVisibility.FOLLOWERS and exists
                    (select 1 from Follower follower where follower.follower.id = :viewerId and follower.followed.id = pick.user.id
                     and follower.status = com.watchwise.watchwise_api.follower.entity.FollowStatus.ACCEPTED)))
            """)
    Page<Pick> findVisibleByOwner(@Param("viewerId") UUID viewerId, @Param("ownerId") UUID ownerId, @Param("templateId") UUID templateId, Pageable pageable);

    @Query(value = """
            select pick from Pick pick
            where pick.picksTemplate.id = :templateId
              and (pick.user.id = :viewerId
                or pick.visibility = com.watchwise.watchwise_api.pick.entity.PickVisibility.PUBLIC
                or (pick.visibility = com.watchwise.watchwise_api.pick.entity.PickVisibility.FOLLOWERS and exists
                    (select 1 from Follower follower where follower.follower.id = :viewerId and follower.followed.id = pick.user.id
                     and follower.status = com.watchwise.watchwise_api.follower.entity.FollowStatus.ACCEPTED)))
            order by pick.createdAt desc, pick.id desc
            """,
            countQuery = """
                    select count(pick) from Pick pick
                    where pick.picksTemplate.id = :templateId
                      and (pick.user.id = :viewerId
                        or pick.visibility = com.watchwise.watchwise_api.pick.entity.PickVisibility.PUBLIC
                        or (pick.visibility = com.watchwise.watchwise_api.pick.entity.PickVisibility.FOLLOWERS and exists
                            (select 1 from Follower follower where follower.follower.id = :viewerId and follower.followed.id = pick.user.id
                             and follower.status = com.watchwise.watchwise_api.follower.entity.FollowStatus.ACCEPTED)))
                    """)
    Page<Pick> findVisibleByTemplateRecent(@Param("viewerId") UUID viewerId,
                                           @Param("templateId") UUID templateId,
                                           Pageable pageable);

    @Query(value = """
            select pick from Pick pick
            where pick.picksTemplate.id = :templateId
              and (pick.user.id = :viewerId
                or pick.visibility = com.watchwise.watchwise_api.pick.entity.PickVisibility.PUBLIC
                or (pick.visibility = com.watchwise.watchwise_api.pick.entity.PickVisibility.FOLLOWERS and exists
                    (select 1 from Follower follower where follower.follower.id = :viewerId and follower.followed.id = pick.user.id
                     and follower.status = com.watchwise.watchwise_api.follower.entity.FollowStatus.ACCEPTED)))
            order by pick.likesCount desc, pick.createdAt desc, pick.id desc
            """,
            countQuery = """
                    select count(pick) from Pick pick
                    where pick.picksTemplate.id = :templateId
                      and (pick.user.id = :viewerId
                        or pick.visibility = com.watchwise.watchwise_api.pick.entity.PickVisibility.PUBLIC
                        or (pick.visibility = com.watchwise.watchwise_api.pick.entity.PickVisibility.FOLLOWERS and exists
                            (select 1 from Follower follower where follower.follower.id = :viewerId and follower.followed.id = pick.user.id
                             and follower.status = com.watchwise.watchwise_api.follower.entity.FollowStatus.ACCEPTED)))
                    """)
    Page<Pick> findVisibleByTemplatePopular(@Param("viewerId") UUID viewerId,
                                            @Param("templateId") UUID templateId,
                                            Pageable pageable);

    boolean existsByPicksTemplateId(UUID picksTemplateId);
}
