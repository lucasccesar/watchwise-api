package com.watchwise.watchwise_api.notification.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.notification.dto.NotificationResponseDTO;
import com.watchwise.watchwise_api.notification.dto.NotificationPageViewDTO;
import com.watchwise.watchwise_api.notification.dto.NotificationViewDTO;
import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.entity.NotificationTargetType;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.notification.mapper.NotificationMapper;
import com.watchwise.watchwise_api.notification.mapper.NotificationMapperImpl;
import com.watchwise.watchwise_api.notification.repository.NotificationCountsProjection;
import com.watchwise.watchwise_api.notification.repository.NotificationRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationViewAssembler notificationViewAssembler;

    @Spy
    private PageRequestFactory pageRequestFactory = new PageRequestFactory();

    @Spy
    private NotificationMapper notificationMapper = buildRealNotificationMapper();

    @InjectMocks
    private NotificationServiceImpl notificationService;

    @Captor
    private ArgumentCaptor<PageRequest> pageRequestCaptor;

    private UUID userId;
    private Notification notification;
    private User latestActor;
    private UUID socialTargetId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        User user = User.builder().id(userId).username("lucas").email("lucas@email.com").password("hashed")
                .isProfilePublic(true).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        Content content = Content.builder().id(UUID.randomUUID()).tmdbId("603").type(ContentType.MOVIE)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        notification = Notification.builder()
                .id(UUID.randomUUID()).user(user).type(NotificationType.RELEASE)
                .message("The Matrix is out now").content(content).isRead(false)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        latestActor = User.builder().id(UUID.randomUUID()).username("actor").email("actor@email.com")
                .password("hashed").profilePicture("https://example.com/actor.png").isProfilePublic(true)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        socialTargetId = UUID.randomUUID();
    }

    @Test
    @DisplayName("[getNotifications] Should Return Mapped Page - When Notifications Exist")
    void shouldReturnMappedPageWhenNotificationsExist() {
        Page<Notification> page = new PageImpl<>(List.of(notification));
        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(eq(userId), any(PageRequest.class))).thenReturn(page);

        Page<NotificationResponseDTO> result = notificationService.getNotifications(userId, null, 1, 10);

        assertThat(result.getContent()).extracting(NotificationResponseDTO::id).containsExactly(notification.getId());
    }

    @Test
    @DisplayName("[getNotifications] Should Map Social Fields And Hide Them For Legacy Notifications")
    void shouldMapSocialFieldsAndHideThemForLegacyNotifications() {
        Notification socialNotification = Notification.builder()
                .id(UUID.randomUUID()).user(notification.getUser()).type(NotificationType.COMMENT_RECEIVED)
                .message("Someone commented on your review").content(null).latestActor(latestActor)
                .targetType(NotificationTargetType.DIARY_ENTRY).targetId(socialTargetId).interactionCount(2)
                .isRead(false).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(eq(userId), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(socialNotification, notification)));

        Page<NotificationResponseDTO> result = notificationService.getNotifications(userId, null, 1, 10);

        NotificationResponseDTO socialResponse = result.getContent().get(0);
        UserPreviewDTO actorResponse = socialResponse.latestActor();
        assertThat(actorResponse.id()).isEqualTo(latestActor.getId());
        assertThat(actorResponse.username()).isEqualTo(latestActor.getUsername());
        assertThat(socialResponse.targetType()).isEqualTo(NotificationTargetType.DIARY_ENTRY);
        assertThat(socialResponse.targetId()).isEqualTo(socialTargetId);
        assertThat(socialResponse.interactionCount()).isEqualTo(2);

        NotificationResponseDTO legacyResponse = result.getContent().get(1);
        assertThat(legacyResponse.latestActor()).isNull();
        assertThat(legacyResponse.targetType()).isNull();
        assertThat(legacyResponse.targetId()).isNull();
        assertThat(legacyResponse.interactionCount()).isNull();
    }

    @Test
    @DisplayName("[getNotifications] Should Filter By isRead - When isRead Is Provided")
    void shouldFilterByIsReadWhenIsReadIsProvided() {
        when(notificationRepository.findByUserIdAndIsReadOrderByCreatedAtDesc(eq(userId), eq(false), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(notification)));

        notificationService.getNotifications(userId, false, 1, 10);

        verify(notificationRepository).findByUserIdAndIsReadOrderByCreatedAtDesc(eq(userId), eq(false), any(PageRequest.class));
        verify(notificationRepository, never()).findByUserIdOrderByCreatedAtDesc(any(), any());
    }

    @Test
    @DisplayName("[getNotifications] Should Return Empty Page - When User Has No Notifications")
    void shouldReturnEmptyPageWhenUserHasNoNotifications() {
        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(eq(userId), any(PageRequest.class))).thenReturn(Page.empty());

        Page<NotificationResponseDTO> result = notificationService.getNotifications(userId, null, 1, 10);

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("[getNotifications] Should Use Default Page - When Page Number Is Null")
    void shouldUseDefaultPageWhenPageNumberIsNull() {
        stubEmptyPage();

        notificationService.getNotifications(userId, null, null, 10);

        verify(notificationRepository).findByUserIdOrderByCreatedAtDesc(eq(userId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(PageRequestFactory.DEFAULT_PAGE);
    }

    @Test
    @DisplayName("[getNotifications] Should Use Default Page - When Page Number Is Zero")
    void shouldUseDefaultPageWhenPageNumberIsZero() {
        stubEmptyPage();

        notificationService.getNotifications(userId, null, 0, 10);

        verify(notificationRepository).findByUserIdOrderByCreatedAtDesc(eq(userId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(PageRequestFactory.DEFAULT_PAGE);
    }

    @Test
    @DisplayName("[getNotifications] Should Use Page Number Minus One - When Page Number Is Positive")
    void shouldUsePageNumberMinusOneWhenPageNumberIsPositive() {
        stubEmptyPage();

        notificationService.getNotifications(userId, null, 3, 10);

        verify(notificationRepository).findByUserIdOrderByCreatedAtDesc(eq(userId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("[getNotifications] Should Throw BadRequestException - When Page Number Is Negative")
    void shouldThrowBadRequestExceptionWhenPageNumberIsNegative() {
        assertThatThrownBy(() -> notificationService.getNotifications(userId, null, -1, 10))
                .isInstanceOf(BadRequestException.class);

        verify(notificationRepository, never()).findByUserIdOrderByCreatedAtDesc(any(), any());
    }

    @Test
    @DisplayName("[getNotifications] Should Use Default Page Size - When Page Size Is Null")
    void shouldUseDefaultPageSizeWhenPageSizeIsNull() {
        stubEmptyPage();

        notificationService.getNotifications(userId, null, 1, null);

        verify(notificationRepository).findByUserIdOrderByCreatedAtDesc(eq(userId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(PageRequestFactory.DEFAULT_PAGE_SIZE);
    }

    @Test
    @DisplayName("[getNotifications] Should Use Provided Page Size - When Page Size Is Valid")
    void shouldUseProvidedPageSizeWhenPageSizeIsValid() {
        stubEmptyPage();

        notificationService.getNotifications(userId, null, 1, 25);

        verify(notificationRepository).findByUserIdOrderByCreatedAtDesc(eq(userId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(25);
    }

    @Test
    @DisplayName("[getNotifications] Should Use Provided Page Size - When Page Size Is At Max Limit")
    void shouldUseProvidedPageSizeWhenPageSizeIsAtMaxLimit() {
        stubEmptyPage();

        notificationService.getNotifications(userId, null, 1, 1000);

        verify(notificationRepository).findByUserIdOrderByCreatedAtDesc(eq(userId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(1000);
    }

    @Test
    @DisplayName("[getNotifications] Should Clamp Page Size To Max Limit - When Page Size Exceeds Limit")
    void shouldClampPageSizeToMaxLimitWhenPageSizeExceedsLimit() {
        stubEmptyPage();

        notificationService.getNotifications(userId, null, 1, 1001);

        verify(notificationRepository).findByUserIdOrderByCreatedAtDesc(eq(userId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(PageRequestFactory.MAX_PAGE_SIZE);
    }

    @Test
    @DisplayName("[getNotificationsView] Should Return Filtered Rows And Global Counts - When Read Filter Is Provided")
    void shouldReturnFilteredRowsAndGlobalCountsWhenReadFilterIsProvided() {
        NotificationViewDTO visualNotification = notificationView(notification.getId());
        when(notificationRepository.findByUserIdAndIsReadOrderByCreatedAtDesc(
                eq(userId), eq(false), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(notification, notification, notification), PageRequest.of(0, 20), 3));
        when(notificationViewAssembler.assemble(any(), eq(userId)))
                .thenReturn(List.of(visualNotification, visualNotification, visualNotification));
        when(notificationRepository.findCountsByUserId(userId)).thenReturn(counts(5, 2));

        NotificationPageViewDTO result = notificationService.getNotificationsView(userId, false, 1, 50);

        assertThat(result.content()).containsExactly(visualNotification, visualNotification, visualNotification);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.totalElements()).isEqualTo(3);
        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.hasNext()).isFalse();
        assertThat(result.totalNotifications()).isEqualTo(5);
        assertThat(result.unreadNotifications()).isEqualTo(2);
        verify(notificationRepository).findByUserIdAndIsReadOrderByCreatedAtDesc(
                eq(userId), eq(false), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(0);
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(20);
        verify(notificationRepository).findCountsByUserId(userId);
    }

    @Test
    @DisplayName("[getNotificationsView] Should Preserve First Page Convention - When Page Is Zero Or One")
    void shouldPreserveFirstPageConventionWhenPageIsZeroOrOne() {
        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(eq(userId), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(notification), PageRequest.of(0, 10), 1));
        when(notificationViewAssembler.assemble(any(), eq(userId))).thenReturn(List.of(notificationView(notification.getId())));
        when(notificationRepository.findCountsByUserId(userId)).thenReturn(counts(1, 1));

        notificationService.getNotificationsView(userId, null, 0, 10);
        notificationService.getNotificationsView(userId, null, 1, 10);

        verify(notificationRepository, times(2))
                .findByUserIdOrderByCreatedAtDesc(eq(userId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getAllValues()).extracting(PageRequest::getPageNumber)
                .containsExactly(0, 0);
    }

    @Test
    @DisplayName("[getNotificationsView] Should Address Second Page - When Page Is Two")
    void shouldAddressSecondPageWhenPageIsTwo() {
        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(eq(userId), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(notification), PageRequest.of(1, 10), 11));
        when(notificationViewAssembler.assemble(any(), eq(userId))).thenReturn(List.of(notificationView(notification.getId())));
        when(notificationRepository.findCountsByUserId(userId)).thenReturn(counts(11, 1));

        NotificationPageViewDTO result = notificationService.getNotificationsView(userId, null, 2, 10);

        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.totalElements()).isEqualTo(11);
        verify(notificationRepository).findByUserIdOrderByCreatedAtDesc(eq(userId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("[getNotificationsView] Should Return Empty Page With Global Counts - When Requested Page Has No Rows")
    void shouldReturnEmptyPageWithGlobalCountsWhenRequestedPageHasNoRows() {
        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(eq(userId), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 20), 1));
        when(notificationViewAssembler.assemble(any(), eq(userId))).thenReturn(List.of());
        when(notificationRepository.findCountsByUserId(userId)).thenReturn(counts(1, 1));

        NotificationPageViewDTO result = notificationService.getNotificationsView(userId, null, 2, 20);

        assertThat(result.content()).isEmpty();
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.hasNext()).isFalse();
        assertThat(result.totalNotifications()).isEqualTo(1);
        assertThat(result.unreadNotifications()).isEqualTo(1);
    }

    @Test
    @DisplayName("[getNotificationsView] Should Reject Invalid Page - When Page Is Negative")
    void shouldRejectInvalidPageWhenPageIsNegative() {
        assertThatThrownBy(() -> notificationService.getNotificationsView(userId, null, -1, 20))
                .isInstanceOf(BadRequestException.class);

        verify(notificationRepository, never()).findCountsByUserId(any());
        verifyNoVisualReads();
    }

    @Test
    @DisplayName("[getNotifications] Should Throw BadRequestException - When Page Size Is Negative")
    void shouldThrowBadRequestExceptionWhenPageSizeIsNegative() {
        assertThatThrownBy(() -> notificationService.getNotifications(userId, null, 1, -5))
                .isInstanceOf(BadRequestException.class);

        verify(notificationRepository, never()).findByUserIdOrderByCreatedAtDesc(any(), any());
    }

    @Test
    @DisplayName("[getNotifications] Should Throw BadRequestException - When Page Size Is Zero")
    void shouldThrowBadRequestExceptionWhenPageSizeIsZero() {
        assertThatThrownBy(() -> notificationService.getNotifications(userId, null, 1, 0))
                .isInstanceOf(BadRequestException.class);

        verify(notificationRepository, never()).findByUserIdOrderByCreatedAtDesc(any(), any());
    }

    @Test
    @DisplayName("[markAsRead] Should Set isRead True And Save - When Notification Belongs To The User")
    void shouldSetIsReadTrueAndSaveWhenNotificationBelongsToTheUser() {
        LocalDateTime originalUpdatedAt = notification.getUpdatedAt();
        when(notificationRepository.findByIdForUpdate(notification.getId())).thenReturn(Optional.of(notification));

        notificationService.markAsRead(userId, notification.getId());

        assertThat(notification.getIsRead()).isTrue();
        assertThat(notification.getUpdatedAt()).isAfterOrEqualTo(originalUpdatedAt);
        verify(notificationRepository).findByIdForUpdate(notification.getId());
        verify(notificationRepository, never()).findById(notification.getId());
        verify(notificationRepository).save(notification);
    }

    @Test
    @DisplayName("[markAsRead] Should Throw NotFoundException - When Notification Does Not Exist")
    void shouldThrowNotFoundExceptionWhenNotificationDoesNotExist() {
        when(notificationRepository.findByIdForUpdate(notification.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.markAsRead(userId, notification.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("[markAsRead] Should Throw ForbiddenException - When Notification Belongs To A Different User")
    void shouldThrowForbiddenExceptionWhenNotificationBelongsToADifferentUser() {
        when(notificationRepository.findByIdForUpdate(notification.getId())).thenReturn(Optional.of(notification));

        assertThatThrownBy(() -> notificationService.markAsRead(UUID.randomUUID(), notification.getId()))
                .isInstanceOf(ForbiddenException.class);

        verify(notificationRepository, never()).save(any());
    }

    private void stubEmptyPage() {
        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(any(), any(PageRequest.class))).thenReturn(Page.empty());
    }

    private NotificationViewDTO notificationView(UUID notificationId) {
        return new NotificationViewDTO(
                notificationId, notification.getType(), notification.getMessage(), null, null,
                notification.getIsRead(), notification.getCreatedAt(), notification.getUpdatedAt(),
                null, null, null, null, null, null, null);
    }

    private NotificationCountsProjection counts(long total, long unread) {
        return new NotificationCountsProjection() {
            @Override
            public long getTotalNotifications() {
                return total;
            }

            @Override
            public long getUnreadNotifications() {
                return unread;
            }
        };
    }

    private void verifyNoVisualReads() {
        verify(notificationViewAssembler, never()).assemble(any(), any());
    }

    private static NotificationMapper buildRealNotificationMapper() {
        NotificationMapperImpl mapper = new NotificationMapperImpl();
        ReflectionTestUtils.setField(mapper, "contentMapper", Mappers.getMapper(ContentMapper.class));
        ReflectionTestUtils.setField(mapper, "userMapper", Mappers.getMapper(UserMapper.class));
        return mapper;
    }
}
