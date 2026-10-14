package com.watchwise.watchwise_api.notification.dto;

import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.notification.entity.NotificationTargetType;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.person.dto.PersonPreviewDTO;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationViewDTOTest {

    @Test
    @DisplayName("[NotificationViewDTO] Should Preserve Raw Fields And Visual Previews - When Record Is Constructed")
    void shouldPreserveRawFieldsAndVisualPreviewsWhenRecordIsConstructed() {
        UUID notificationId = UUID.fromString("8e0d7d7a-3c73-4e08-84ec-c6b197a8a4bb");
        UUID contentId = UUID.fromString("6c810a46-4107-4a41-bbe4-4b5d5c4edb4d");
        UUID actorId = UUID.fromString("df0bd50e-b8ef-458a-a5da-20a2a9a6761c");
        UUID targetId = UUID.fromString("96a2c229-c656-4c2f-98c4-67edca1d03af");
        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 14, 9, 15);
        LocalDateTime updatedAt = LocalDateTime.of(2026, 10, 14, 9, 20);
        ContentRefDTO content = new ContentRefDTO(
                contentId,
                "550",
                ContentType.MOVIE,
                null,
                null,
                null,
                null,
                null,
                createdAt,
                updatedAt);
        ContentCardDTO contentCard = new ContentCardDTO(
                contentId,
                ContentType.MOVIE,
                "550",
                null,
                null,
                null,
                "Fight Club",
                "/fight-club.jpg",
                null,
                null,
                1999,
                139,
                null,
                null,
                null,
                null,
                null,
                null,
                ContentPreviewStatus.AVAILABLE);
        UserPreviewDTO latestActor = new UserPreviewDTO(
                actorId,
                "lucas",
                "Lucas",
                "/lucas.jpg",
                true);
        PersonPreviewDTO person = new PersonPreviewDTO(
                "287",
                "Brad Pitt",
                "/brad-pitt.jpg",
                ContentPreviewStatus.AVAILABLE);
        NotificationTargetPreviewDTO targetPreview = new NotificationTargetPreviewDTO(
                NotificationTargetType.PICKS_TEMPLATE,
                targetId,
                "Best Movies",
                "A community template",
                null,
                "/template-cover.jpg",
                null,
                ContentPreviewStatus.AVAILABLE);

        NotificationViewDTO notification = new NotificationViewDTO(
                notificationId,
                NotificationType.FOLLOWED_PERSON_NEW_CREDIT,
                "Brad Pitt appeared in a new movie",
                content,
                "287",
                true,
                createdAt,
                updatedAt,
                latestActor,
                NotificationTargetType.PICKS_TEMPLATE,
                targetId,
                3,
                contentCard,
                person,
                targetPreview);

        assertThat(notification.id()).isEqualTo(notificationId);
        assertThat(notification.type()).isEqualTo(NotificationType.FOLLOWED_PERSON_NEW_CREDIT);
        assertThat(notification.message()).isEqualTo("Brad Pitt appeared in a new movie");
        assertThat(notification.content()).isSameAs(content);
        assertThat(notification.personTmdbId()).isEqualTo("287");
        assertThat(notification.isRead()).isTrue();
        assertThat(notification.createdAt()).isEqualTo(createdAt);
        assertThat(notification.updatedAt()).isEqualTo(updatedAt);
        assertThat(notification.latestActor()).isSameAs(latestActor);
        assertThat(notification.targetType()).isEqualTo(NotificationTargetType.PICKS_TEMPLATE);
        assertThat(notification.targetId()).isEqualTo(targetId);
        assertThat(notification.interactionCount()).isEqualTo(3);
        assertThat(notification.contentCard()).isSameAs(contentCard);
        assertThat(notification.person()).isSameAs(person);
        assertThat(notification.targetPreview()).isSameAs(targetPreview);
        assertThat(targetPreview.title()).isEqualTo("Best Movies");
        assertThat(targetPreview.excerpt()).isEqualTo("A community template");
        assertThat(targetPreview.score()).isNull();
        assertThat(targetPreview.coverImageUrl()).isEqualTo("/template-cover.jpg");
        assertThat(targetPreview.contentCard()).isNull();
        assertThat(targetPreview.previewStatus()).isEqualTo(ContentPreviewStatus.AVAILABLE);
    }

    @Test
    @DisplayName("[NotificationPageViewDTO] Should Preserve Filtered And Global Counts - When Page Is Constructed")
    void shouldPreserveFilteredAndGlobalCountsWhenPageIsConstructed() {
        NotificationViewDTO notification = new NotificationViewDTO(
                UUID.fromString("8e0d7d7a-3c73-4e08-84ec-c6b197a8a4bb"),
                NotificationType.RELEASE,
                "New release",
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        NotificationPageViewDTO page = new NotificationPageViewDTO(
                List.of(notification),
                2,
                20,
                5,
                1,
                false,
                42,
                7);

        assertThat(page.content()).containsExactly(notification);
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.totalElements()).isEqualTo(5);
        assertThat(page.totalPages()).isEqualTo(1);
        assertThat(page.hasNext()).isFalse();
        assertThat(page.totalNotifications()).isEqualTo(42);
        assertThat(page.unreadNotifications()).isEqualTo(7);
    }
}
