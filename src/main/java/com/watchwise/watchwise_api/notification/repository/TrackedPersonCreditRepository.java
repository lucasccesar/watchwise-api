package com.watchwise.watchwise_api.notification.repository;

import com.watchwise.watchwise_api.notification.entity.TrackedPersonCredit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TrackedPersonCreditRepository extends JpaRepository<TrackedPersonCredit, UUID> {

    List<TrackedPersonCredit> findByTrackedPersonStateId(UUID trackedPersonStateId);

    boolean existsByTrackedPersonStateIdAndCreditTmdbId(UUID trackedPersonStateId, String creditTmdbId);

}
