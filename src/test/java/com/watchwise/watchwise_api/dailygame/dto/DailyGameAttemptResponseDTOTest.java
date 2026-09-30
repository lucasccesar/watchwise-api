package com.watchwise.watchwise_api.dailygame.dto;

import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;

class DailyGameAttemptResponseDTOTest {

    @Test
    void legacySixteenArgumentConstructorSetsCurrentAttemptToNull() {
        DailyGameAttemptResponseDTO response = new DailyGameAttemptResponseDTO(
                DailyGameType.MOVIE_BY_POSTER,
                DailyGameTargetKind.MOVIE,
                5,
                1,
                4,
                DailyGameViewStatus.IN_PROGRESS,
                null,
                List.of(),
                0,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null);

        assertNull(response.currentAttempt());
    }
}
