package com.watchwise.watchwise_api.calendar.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarScheduleCadenceTest {

    @Test
    @DisplayName("[nextCheckAt] Should Keep Tomorrow Due - When UTC Has Advanced But The Civil Day Has Not")
    void shouldKeepTomorrowDueWhenUtcHasAdvancedButTheCivilDayHasNot() {
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("America/Sao_Paulo")));
        try {
            Instant checkedAt = Instant.parse("2026-09-13T01:30:00Z");

            Instant nextCheckAt = CalendarScheduleCadence.nextCheckAt(LocalDate.of(2026, 9, 13), checkedAt);

            assertThat(nextCheckAt).isEqualTo(checkedAt.plusSeconds(24 * 60 * 60));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    @DisplayName("[nextSeriesCheckAt] Should Use The Civil Day - When The Series Releases Tomorrow Locally")
    void shouldUseTheCivilDayWhenTheSeriesReleasesTomorrowLocally() {
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("America/Sao_Paulo")));
        try {
            Instant checkedAt = Instant.parse("2026-09-13T01:30:00Z");

            Instant nextCheckAt = CalendarScheduleCadence.nextSeriesCheckAt(LocalDate.of(2026, 9, 13), checkedAt);

            assertThat(nextCheckAt).isEqualTo(checkedAt.plusSeconds(24 * 60 * 60));
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
