package com.watchwise.watchwise_api.notification.controller;

import com.watchwise.watchwise_api.notification.dto.NotificationResponseDTO;
import com.watchwise.watchwise_api.notification.service.NotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private NotificationController notificationController;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("[getNotifications] Should Return 200 With Mapped Page - When Called")
    void shouldReturn200WithMappedPageWhenCalled() {
        when(notificationService.getNotifications(eq(userId), eq(null), eq(1), eq(10)))
                .thenReturn(new PageImpl<>(List.<NotificationResponseDTO>of()));

        ResponseEntity<?> response = notificationController.getNotifications(null, 1, 10);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("[getNotifications] Should Resolve Current User From SecurityContext - When Called")
    void shouldResolveCurrentUserFromSecurityContextWhenCalled() {
        when(notificationService.getNotifications(any(), any(), any(), any())).thenReturn(Page.empty());

        notificationController.getNotifications(true, 1, 10);

        verify(notificationService).getNotifications(userId, true, 1, 10);
    }

    @Test
    @DisplayName("[markAsRead] Should Return 204 And Delegate To Service - When Called")
    void shouldReturn204AndDelegateToServiceWhenCalled() {
        UUID notificationId = UUID.randomUUID();

        ResponseEntity<Void> response = notificationController.markAsRead(notificationId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(notificationService).markAsRead(userId, notificationId);
    }
}
