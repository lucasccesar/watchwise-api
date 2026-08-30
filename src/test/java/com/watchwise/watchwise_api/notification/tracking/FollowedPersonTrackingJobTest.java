package com.watchwise.watchwise_api.notification.tracking;

import com.watchwise.watchwise_api.notification.service.FollowedPersonTrackingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class FollowedPersonTrackingJobTest {

    @Mock
    private FollowedPersonTrackingService followedPersonTrackingService;

    @InjectMocks
    private FollowedPersonTrackingJob followedPersonTrackingJob;

    @Test
    @DisplayName("[run] Should Delegate To FollowedPersonTrackingService - When Called")
    void shouldDelegateToFollowedPersonTrackingServiceWhenCalled() {
        followedPersonTrackingJob.run();

        verify(followedPersonTrackingService).trackFollowedPeopleCredits();
    }
}
