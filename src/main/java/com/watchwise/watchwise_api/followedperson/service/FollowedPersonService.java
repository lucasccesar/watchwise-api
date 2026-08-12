package com.watchwise.watchwise_api.followedperson.service;

import org.springframework.data.domain.Page;

import java.util.UUID;

public interface FollowedPersonService {

    void followPerson(UUID userId, String personTmdbId);

    void unfollowPerson(UUID userId, String personTmdbId);

    Page<String> getFollowedPeople(UUID viewerId, UUID targetUserId, Integer pageNumber, Integer pageSize);

}