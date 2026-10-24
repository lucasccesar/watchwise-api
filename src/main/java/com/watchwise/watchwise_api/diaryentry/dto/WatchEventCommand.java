package com.watchwise.watchwise_api.diaryentry.dto;

import com.watchwise.watchwise_api.content.dto.MediaReference;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record WatchEventCommand(
        MediaReference content,
        LocalDate watchedDate,
        Integer requestedWatchNumber,
        String comment,
        Integer score,
        Boolean watchedInTheater,
        List<UUID> watchedWith,
        Instant watchedAt) {
}
