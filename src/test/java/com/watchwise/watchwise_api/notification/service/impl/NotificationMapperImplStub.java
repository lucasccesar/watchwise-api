package com.watchwise.watchwise_api.notification.service.impl;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.notification.dto.NotificationResponseDTO;
import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.mapper.NotificationMapper;

class NotificationMapperImplStub implements NotificationMapper {
    @Override
    public NotificationResponseDTO notificationToNotificationResponseDto(Notification notification) {
        ContentRefDTO contentRef = new ContentRefDTO(
                notification.getContent().getId(), notification.getContent().getTmdbId(),
                notification.getContent().getType(), notification.getContent().getSeriesTmdbId(),
                notification.getContent().getSeasonNumber(), notification.getContent().getEpisodeNumber(),
                notification.getContent().getIsSeasonFinale(), notification.getContent().getIsSeriesFinale(),
                notification.getContent().getCreatedAt(), notification.getContent().getUpdatedAt(),
                notification.getContent().getRuntimeMinutes(), notification.getContent().getGenres(),
                notification.getContent().getReleaseYear(), notification.getContent().getCountries());
        return new NotificationResponseDTO(
                notification.getId(), notification.getType(), notification.getMessage(), contentRef,
                notification.getPersonTmdbId(), notification.getIsRead(),
                notification.getCreatedAt(), notification.getUpdatedAt());
    }
}
