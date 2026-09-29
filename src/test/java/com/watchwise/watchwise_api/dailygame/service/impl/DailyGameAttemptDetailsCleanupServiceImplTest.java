package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.dailygame.repository.UserDailyGameResultRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailyGameAttemptDetailsCleanupServiceImplTest {

    @Mock
    private UserDailyGameResultRepository resultRepository;

    @InjectMocks
    private DailyGameAttemptDetailsCleanupServiceImpl cleanupService;

    @Test
    @DisplayName("[cleanup] Should Delegate The Current Date And Return Cleared Row Count")
    void shouldDelegateTheCurrentDateAndReturnClearedRowCount() {
        LocalDate currentDate = LocalDate.of(2026, 9, 30);
        when(resultRepository.clearAttemptDetailsBefore(currentDate)).thenReturn(4);

        int cleared = cleanupService.cleanup(currentDate);

        assertThat(cleared).isEqualTo(4);
        verify(resultRepository).clearAttemptDetailsBefore(currentDate);
        verifyNoMoreInteractions(resultRepository);
    }
}
