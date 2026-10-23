package com.watchwise.watchwise_api.diarycompletion.repository;

import com.watchwise.watchwise_api.diarycompletion.entity.DiaryCompletionProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface DiaryCompletionProjectionRepository extends JpaRepository<DiaryCompletionProjection, UUID> {
    List<DiaryCompletionProjection> findByUserIdAndContentIdOrderByWatchNumber(UUID userId, UUID contentId);
}
