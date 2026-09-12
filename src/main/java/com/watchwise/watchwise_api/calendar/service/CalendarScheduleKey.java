package com.watchwise.watchwise_api.calendar.service;

import com.watchwise.watchwise_api.content.entity.ContentType;

public record CalendarScheduleKey(
        ContentType type,
        String tmdbId,
        String preferredLanguage,
        String preferredRegion) {
}
