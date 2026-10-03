package com.watchwise.watchwise_api.contentreleasedatesnapshot.tracking;

import com.watchwise.watchwise_api.contentreleasedatesnapshot.service.ContentReleaseDateSnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ContentReleaseDateSnapshotRefreshJob {

    private final ContentReleaseDateSnapshotService snapshotService;

    @Scheduled(cron = "${app.content-release-date-snapshot-refresh.cron}")
    public void run() {
        snapshotService.refreshDue();
    }
}
