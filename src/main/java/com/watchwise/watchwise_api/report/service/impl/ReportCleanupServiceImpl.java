package com.watchwise.watchwise_api.report.service.impl;

import com.watchwise.watchwise_api.report.repository.ReportCaseRepository;
import com.watchwise.watchwise_api.report.service.ReportCleanupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ReportCleanupServiceImpl implements ReportCleanupService {

    static final int BATCH_SIZE = 500;
    static final int RETENTION_DAYS = 90;

    private final ReportCaseRepository reportCaseRepository;
    private final Clock clock;

    @Override
    public int removeExpiredCases() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(RETENTION_DAYS);
        int totalDeleted = 0;
        int deleted;
        do {
            deleted = reportCaseRepository.deleteClosedBefore(cutoff, BATCH_SIZE);
            totalDeleted += deleted;
        } while (deleted == BATCH_SIZE);
        return totalDeleted;
    }
}
