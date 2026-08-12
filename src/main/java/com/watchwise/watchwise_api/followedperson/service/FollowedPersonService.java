package com.watchwise.watchwise_api.followedperson.service;

import java.util.UUID;

public interface FollowedPersonService {

    void followPerson(UUID userId, String personTmdbId);

    void unfollowPerson(UUID userId, String personTmdbId);

}