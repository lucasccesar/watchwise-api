package com.watchwise.watchwise_api.notification.service;

import com.watchwise.watchwise_api.content.entity.Content;

public interface ContentTrackingService {

    void trackContentChanges();

    void reactivateAfterRevival(Content content, String freshStatus);

}
