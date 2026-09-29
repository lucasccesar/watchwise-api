package com.watchwise.watchwise_api.dailygame.tracking;

import com.watchwise.watchwise_api.dailygame.service.DailyGameAttemptDetailsCleanupService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class DailyGameAttemptDetailsCleanupJobTest {

    @Mock
    private DailyGameAttemptDetailsCleanupService cleanupService;

    @Test
    @DisplayName("[run] Should Clean Up Before The Current UTC Date")
    void shouldCleanUpBeforeTheCurrentUtcDate() {
        DailyGameAttemptDetailsCleanupJob cleanupJob = new DailyGameAttemptDetailsCleanupJob(
                cleanupService,
                Clock.fixed(Instant.parse("2026-09-30T00:05:00Z"), ZoneOffset.UTC));

        cleanupJob.run();

        verify(cleanupService).cleanup(LocalDate.of(2026, 9, 30));
        verifyNoMoreInteractions(cleanupService);
    }
}
