package com.watchwise.watchwise_api.notification.tracking;

import com.watchwise.watchwise_api.notification.entity.NotificationType;

import java.time.LocalDate;

public record ContentChangeEvent(
        NotificationType type,
        LocalDate relevantDate,
        Integer seasonNumber,
        Integer episodeNumber) {
}
