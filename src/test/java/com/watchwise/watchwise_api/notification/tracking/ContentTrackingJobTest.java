package com.watchwise.watchwise_api.notification.tracking;

import com.watchwise.watchwise_api.notification.service.ContentTrackingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ContentTrackingJobTest {

    @Mock
    private ContentTrackingService contentTrackingService;

    @InjectMocks
    private ContentTrackingJob contentTrackingJob;

    @Test
    @DisplayName("[run] Should Delegate To ContentTrackingService - When Called")
    void shouldDelegateToContentTrackingServiceWhenCalled() {
        contentTrackingJob.run();

        verify(contentTrackingService).trackContentChanges();
    }
}
