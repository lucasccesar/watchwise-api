package com.watchwise.watchwise_api.report.repository;

import com.watchwise.watchwise_api.report.entity.ReportCase;
import com.watchwise.watchwise_api.report.entity.ReportCaseStatus;
import com.watchwise.watchwise_api.report.entity.ReportTargetType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ReportCaseRepository extends JpaRepository<ReportCase, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT reportCase FROM ReportCase reportCase
            WHERE reportCase.targetType = :targetType
              AND reportCase.targetId = :targetId
              AND reportCase.status IN (com.watchwise.watchwise_api.report.entity.ReportCaseStatus.OPEN,
                                        com.watchwise.watchwise_api.report.entity.ReportCaseStatus.IN_REVIEW)
            """)
    Optional<ReportCase> findActiveForUpdate(@Param("targetType") ReportTargetType targetType,
            @Param("targetId") UUID targetId);

    Page<ReportCase> findByStatusOrderByCreatedAtAscIdAsc(ReportCaseStatus status, Pageable pageable);
}
