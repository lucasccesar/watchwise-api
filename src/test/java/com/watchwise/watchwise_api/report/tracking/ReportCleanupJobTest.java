package com.watchwise.watchwise_api.report.tracking;

import com.watchwise.watchwise_api.report.service.ReportCleanupService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ReportCleanupJobTest {

    @Mock ReportCleanupService cleanupService;
    @InjectMocks ReportCleanupJob job;

    @Test
    void delegatesScheduledCleanupToTheService() {
        job.removeExpiredCases();
        verify(cleanupService).removeExpiredCases();
    }
}
