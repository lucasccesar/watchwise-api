package com.watchwise.watchwise_api.report.service.impl;

import com.watchwise.watchwise_api.report.repository.ReportCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportCleanupServiceImplTest {

    @Mock ReportCaseRepository reportCaseRepository;
    ReportCleanupServiceImpl cleanupService;
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        cleanupService = new ReportCleanupServiceImpl(reportCaseRepository, clock);
    }

    @Test
    void removesExpiredCasesInBoundedBatches() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(90);
        when(reportCaseRepository.deleteClosedBefore(cutoff, 500)).thenReturn(500, 500, 3);

        assertThat(cleanupService.removeExpiredCases()).isEqualTo(1003);

        verify(reportCaseRepository, times(3)).deleteClosedBefore(cutoff, 500);
    }

    @Test
    void stopsAfterTheFirstPartialBatch() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(90);
        when(reportCaseRepository.deleteClosedBefore(cutoff, 500)).thenReturn(0);

        assertThat(cleanupService.removeExpiredCases()).isZero();

        verify(reportCaseRepository).deleteClosedBefore(cutoff, 500);
    }
}
