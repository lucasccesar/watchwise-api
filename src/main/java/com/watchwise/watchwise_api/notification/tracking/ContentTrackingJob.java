package com.watchwise.watchwise_api.notification.tracking;

import com.watchwise.watchwise_api.notification.service.ContentTrackingService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ContentTrackingJob {

    private final ContentTrackingService contentTrackingService;

    @Scheduled(cron = "${app.content-tracking.cron}")
    public void run() {
        contentTrackingService.trackContentChanges();
    }
}
