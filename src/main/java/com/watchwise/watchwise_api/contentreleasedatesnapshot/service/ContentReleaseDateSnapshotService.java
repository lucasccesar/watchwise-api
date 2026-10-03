package com.watchwise.watchwise_api.contentreleasedatesnapshot.service;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.watchlist.entity.WatchlistEntry;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface ContentReleaseDateSnapshotService {

    WatchlistDateResolution resolve(User owner, List<WatchlistEntry> entries);

    void writeThrough(User owner, Content content);

    long countUpcoming(UUID userId, ContentType type, String region, LocalDate today);

    void refreshDue();

    record WatchlistDateResolution(Map<UUID, LocalDate> releaseDates) {
    }
}
