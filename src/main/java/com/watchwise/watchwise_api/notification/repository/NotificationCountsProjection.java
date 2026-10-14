package com.watchwise.watchwise_api.notification.repository;

public interface NotificationCountsProjection {

    long getTotalNotifications();

    long getUnreadNotifications();
}
