package com.watchwise.watchwise_api.dailygame.service;

import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameCandidateDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameGuessFeedbackDTO;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DailyGameAttemptDetailsCodecTest {

    private final DailyGameAttemptDetailsCodec codec = new DailyGameAttemptDetailsCodec();

    @Test
    void shouldAppendToAFreshMapAndReadAnImmutableAttemptList() {
        DailyGameAttemptDTO attempt = new DailyGameAttemptDTO(
                1,
                new DailyGameCandidateDTO(
                        DailyGameTargetKind.MOVIE, "550", null, null, null, null,
                        "Fight Club", "https://image.test/fight.jpg", LocalDate.of(1999, 10, 15)),
                new DailyGameGuessFeedbackDTO(true, true, true, true), null, null);
        Map<String, Object> original = new HashMap<>(Map.of("keep", "me"));

        Map<String, Object> encoded = codec.append(original, attempt);

        assertThat(encoded).isNotSameAs(original);
        assertThat(original).containsExactlyEntriesOf(Map.of("keep", "me"));
        assertThat(codec.read(encoded)).containsExactly(attempt);
        assertThatThrownBy(() -> codec.read(encoded).add(attempt))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldReturnAnImmutableEmptyListWhenAttemptDetailsAreAbsent() {
        List<DailyGameAttemptDTO> attempts = codec.read(null);

        assertThat(attempts).isEmpty();
        assertThatThrownBy(() -> attempts.add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
