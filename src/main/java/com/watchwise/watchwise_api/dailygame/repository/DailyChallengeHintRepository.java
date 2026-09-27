package com.watchwise.watchwise_api.dailygame.repository;

import com.watchwise.watchwise_api.dailygame.entity.DailyChallengeHint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DailyChallengeHintRepository extends JpaRepository<DailyChallengeHint, UUID> {

    List<DailyChallengeHint> findByDailyChallengeIdOrderByPositionAsc(UUID dailyChallengeId);

    List<DailyChallengeHint> findByDailyChallengeIdInOrderByDailyChallengeIdAscPositionAsc(
            Collection<UUID> dailyChallengeIds);
}
