package com.watchwise.watchwise_api.notification.service;

import com.watchwise.watchwise_api.user.entity.User;

import java.util.Collection;

public interface FollowNotificationService {

    void notifyFollowRequested(User requester, User profileOwner);

    void notifyNewFollower(User follower, User profileOwner);

    void notifyFollowRequestsAccepted(User profileOwner, Collection<User> requesters);
}
