package com.watchwise.watchwise_api.dailygame.tracking;

import com.watchwise.watchwise_api.dailygame.generation.DailyGameGenerationJob;
import com.watchwise.watchwise_api.dailygame.service.DailyChallengeGenerationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DailyGameGenerationJobTest {

    @Mock
    private DailyChallengeGenerationService generationService;

    @Test
    @DisplayName("[run] Should Generate Today And Tomorrow - When The Injected UTC Clock Is Near Midnight")
    void shouldGenerateTodayAndTomorrowWhenTheInjectedUTCClockIsNearMidnight() {
        DailyGameGenerationJob job = new DailyGameGenerationJob(generationService,
                Clock.fixed(Instant.parse("2026-09-27T23:59:59Z"), ZoneOffset.UTC));

        job.run();

        verify(generationService).ensureGenerated(LocalDate.of(2026, 9, 27));
        verify(generationService).ensureGenerated(LocalDate.of(2026, 9, 28));
    }

    @Test
    @DisplayName("[run] Should Start The New Date - When The Injected UTC Clock Reaches Midnight")
    void shouldStartTheNewDateWhenTheInjectedUTCClockReachesMidnight() {
        DailyGameGenerationJob job = new DailyGameGenerationJob(generationService,
                Clock.fixed(Instant.parse("2026-09-28T00:00:00Z"), ZoneOffset.UTC));

        job.run();

        verify(generationService).ensureGenerated(LocalDate.of(2026, 9, 28));
        verify(generationService).ensureGenerated(LocalDate.of(2026, 9, 29));
    }

    @Test
    @DisplayName("[run] Should Continue With Tomorrow - When Today Generation Fails")
    void shouldContinueWithTomorrowWhenTodayGenerationFails() {
        doThrow(new IllegalStateException("TMDB unavailable"))
                .when(generationService).ensureGenerated(LocalDate.of(2026, 9, 27));
        DailyGameGenerationJob job = new DailyGameGenerationJob(generationService,
                Clock.fixed(Instant.parse("2026-09-27T23:59:59Z"), ZoneOffset.UTC));

        job.run();

        verify(generationService).ensureGenerated(LocalDate.of(2026, 9, 28));
    }
}
