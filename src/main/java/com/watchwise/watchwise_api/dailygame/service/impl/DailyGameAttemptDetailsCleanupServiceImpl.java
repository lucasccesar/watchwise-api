package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.dailygame.repository.UserDailyGameResultRepository;
import com.watchwise.watchwise_api.dailygame.service.DailyGameAttemptDetailsCleanupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class DailyGameAttemptDetailsCleanupServiceImpl implements DailyGameAttemptDetailsCleanupService {

    private final UserDailyGameResultRepository resultRepository;

    @Override
    @Transactional
    public int cleanup(LocalDate currentDate) {
        return resultRepository.clearAttemptDetailsBefore(currentDate);
    }
}
