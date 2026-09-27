package com.watchwise.watchwise_api.dailygame.generation;

import com.watchwise.watchwise_api.dailygame.service.DailyChallengeGenerationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

@Slf4j
@Component
public class DailyGameGenerationJob {

    private final DailyChallengeGenerationService generationService;
    private final Clock clock;

    public DailyGameGenerationJob(DailyChallengeGenerationService generationService, Clock clock) {
        this.generationService = generationService;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.daily-games.generation.cron}")
    public void run() {
        LocalDate today = LocalDate.now(clock);
        generateForDate(today);
        generateForDate(today.plusDays(1));
    }

    private void generateForDate(LocalDate challengeDate) {
        try {
            generationService.ensureGenerated(challengeDate);
        } catch (RuntimeException exception) {
            log.error("Daily game generation failed for {}", challengeDate, exception);
        }
    }
}
