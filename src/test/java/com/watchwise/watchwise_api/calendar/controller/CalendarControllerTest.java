package com.watchwise.watchwise_api.calendar.controller;

import com.watchwise.watchwise_api.calendar.dto.CalendarResponseDTO;
import com.watchwise.watchwise_api.calendar.service.CalendarService;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalendarControllerTest {

    @Mock
    private CalendarService calendarService;

    @InjectMocks
    private CalendarController calendarController;

    private UUID currentUserId;

    @BeforeEach
    void setUp() {
        currentUserId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUserId, null, List.of())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("[getMonth] Should Return The Calendar Response - When Month Is Valid")
    void shouldReturnTheCalendarResponseWhenMonthIsValid() {
        YearMonth month = YearMonth.of(2026, 9);
        CalendarResponseDTO expected = new CalendarResponseDTO(month, "BR", List.of());
        when(calendarService.getMonth(currentUserId, month)).thenReturn(expected);

        ResponseEntity<CalendarResponseDTO> result = calendarController.getMonth("2026-09");

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(expected);
        verify(calendarService).getMonth(currentUserId, month);
    }

    @Test
    @DisplayName("[getMonth] Should Resolve The Authenticated User And Parse The Month - When Called")
    void shouldResolveTheAuthenticatedUserAndParseTheMonthWhenCalled() {
        YearMonth month = YearMonth.of(2026, 2);
        when(calendarService.getMonth(currentUserId, month))
                .thenReturn(new CalendarResponseDTO(month, "BR", List.of()));

        calendarController.getMonth("2026-02");

        verify(calendarService).getMonth(currentUserId, month);
    }

    @Test
    @DisplayName("[getMonth] Should Reject The Request Before Delegating - When Month Is Malformed")
    void shouldRejectTheRequestBeforeDelegatingWhenMonthIsMalformed() {
        assertThatThrownBy(() -> calendarController.getMonth("2026-13"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("month must be in YYYY-MM format");

        verifyNoInteractions(calendarService);
    }

    @Test
    @DisplayName("[getMonth] Should Reject The Request Before Delegating - When Month Does Not Use Two-Digit Month")
    void shouldRejectTheRequestBeforeDelegatingWhenMonthDoesNotUseTwoDigitMonth() {
        assertThatThrownBy(() -> calendarController.getMonth("2026-9"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("month must be in YYYY-MM format");

        verifyNoInteractions(calendarService);
    }

    @Test
    @DisplayName("[getMonth] Should Reject Signed Or Expanded Years")
    void shouldRejectSignedOrExpandedYears() {
        assertThatThrownBy(() -> calendarController.getMonth("+10000-09"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("month must be in YYYY-MM format");

        verifyNoInteractions(calendarService);
    }
}
