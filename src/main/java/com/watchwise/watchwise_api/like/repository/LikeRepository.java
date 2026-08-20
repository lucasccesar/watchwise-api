package com.watchwise.watchwise_api.like.repository;

import com.watchwise.watchwise_api.like.entity.Like;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LikeRepository extends JpaRepository<Like, UUID> {

    Optional<Like> findByUserIdAndCommentId(UUID userId, UUID commentId);

    Optional<Like> findByUserIdAndDiaryEntryId(UUID userId, UUID diaryEntryId);

}
