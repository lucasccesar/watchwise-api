package com.watchwise.watchwise_api.dailygame.service;

import java.time.LocalDate;

public interface DailyGameAttemptDetailsCleanupService {

    int cleanup(LocalDate currentDate);
}
