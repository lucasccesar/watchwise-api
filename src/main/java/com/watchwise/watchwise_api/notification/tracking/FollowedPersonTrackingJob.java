package com.watchwise.watchwise_api.notification.tracking;

import com.watchwise.watchwise_api.notification.service.FollowedPersonTrackingService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FollowedPersonTrackingJob {

    private final FollowedPersonTrackingService followedPersonTrackingService;

    @Scheduled(cron = "${app.followed-person-tracking.cron}")
    public void run() {
        followedPersonTrackingService.trackFollowedPeopleCredits();
    }
}
