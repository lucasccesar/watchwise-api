package com.watchwise.watchwise_api.pickstemplate.repository;

import com.watchwise.watchwise_api.pickstemplate.entity.PickOrigin;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface PicksTemplateRepository extends JpaRepository<PicksTemplate, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select template from PicksTemplate template where template.id = :id")
    Optional<PicksTemplate> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            select template from PicksTemplate template
            where (:origin is null or template.origin = :origin)
              and lower(template.name) like concat('%', lower(coalesce(:escapedName, '')), '%') escape '\\'
            order by lower(template.name), template.id
            """)
    Page<PicksTemplate> search(@Param("origin") PickOrigin origin, @Param("escapedName") String escapedName, Pageable pageable);

    @Query(value = """
            select template from PicksTemplate template
            where (:origin is null or template.origin = :origin)
              and lower(template.name) like concat('%', lower(coalesce(:escapedName, '')), '%') escape '\\'
            order by template.createdAt desc, template.id desc
            """,
            countQuery = """
                    select count(template) from PicksTemplate template
                    where (:origin is null or template.origin = :origin)
                      and lower(template.name) like concat('%', lower(coalesce(:escapedName, '')), '%') escape '\\'
                    """)
    Page<PicksTemplate> searchRecent(@Param("origin") PickOrigin origin,
                                     @Param("escapedName") String escapedName,
                                     Pageable pageable);

    @Query(value = """
            select template from PicksTemplate template
            where (:origin is null or template.origin = :origin)
              and lower(template.name) like concat('%', lower(coalesce(:escapedName, '')), '%') escape '\\'
            order by (
                select count(pick) from Pick pick
                where pick.picksTemplate = template
                  and (pick.user.id = :viewerId
                    or pick.visibility = com.watchwise.watchwise_api.pick.entity.PickVisibility.PUBLIC
                    or (pick.visibility = com.watchwise.watchwise_api.pick.entity.PickVisibility.FOLLOWERS
                        and exists (
                            select follower.id from Follower follower
                            where follower.follower.id = :viewerId
                              and follower.followed.id = pick.user.id
                              and follower.status = com.watchwise.watchwise_api.follower.entity.FollowStatus.ACCEPTED
                        )))
            ) desc, template.createdAt desc, template.id desc
            """,
            countQuery = """
                    select count(template) from PicksTemplate template
                    where (:origin is null or template.origin = :origin)
                      and lower(template.name) like concat('%', lower(coalesce(:escapedName, '')), '%') escape '\\'
                    """)
    Page<PicksTemplate> searchMostPicked(@Param("viewerId") UUID viewerId,
                                         @Param("origin") PickOrigin origin,
                                         @Param("escapedName") String escapedName,
                                         Pageable pageable);

    @Query(value = """
            select template from PicksTemplate template
            where (:origin is null or template.origin = :origin)
              and lower(template.name) like concat('%', lower(coalesce(:escapedName, '')), '%') escape '\\'
            order by (
                (select count(templateLike) from Like templateLike
                 where templateLike.picksTemplate = template
                   and templateLike.createdAt >= :since
                   and templateLike.createdAt <= current_timestamp)
                +
                (select count(templateComment) from Comment templateComment
                 where templateComment.picksTemplate = template
                   and templateComment.createdAt >= :since
                   and templateComment.createdAt <= current_timestamp)
                +
                (select count(pick) from Pick pick
                 where pick.picksTemplate = template
                   and pick.createdAt >= :since
                   and pick.createdAt <= current_timestamp
                   and (pick.user.id = :viewerId
                     or pick.visibility = com.watchwise.watchwise_api.pick.entity.PickVisibility.PUBLIC
                     or (pick.visibility = com.watchwise.watchwise_api.pick.entity.PickVisibility.FOLLOWERS
                         and exists (
                             select follower.id from Follower follower
                             where follower.follower.id = :viewerId
                               and follower.followed.id = pick.user.id
                               and follower.status = com.watchwise.watchwise_api.follower.entity.FollowStatus.ACCEPTED
                         )))
                )
            ) desc, template.createdAt desc, template.id desc
            """,
            countQuery = """
                    select count(template) from PicksTemplate template
                    where (:origin is null or template.origin = :origin)
                      and lower(template.name) like concat('%', lower(coalesce(:escapedName, '')), '%') escape '\\'
                    """)
    Page<PicksTemplate> searchPopularWeek(@Param("viewerId") UUID viewerId,
                                          @Param("since") LocalDateTime since,
                                          @Param("origin") PickOrigin origin,
                                          @Param("escapedName") String escapedName,
                                          Pageable pageable);
}
