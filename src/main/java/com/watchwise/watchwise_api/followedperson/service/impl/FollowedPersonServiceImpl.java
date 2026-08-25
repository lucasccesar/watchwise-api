package com.watchwise.watchwise_api.followedperson.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.followedperson.entity.FollowedPerson;
import com.watchwise.watchwise_api.followedperson.repository.FollowedPersonRepository;
import com.watchwise.watchwise_api.followedperson.service.FollowedPersonService;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FollowedPersonServiceImpl implements FollowedPersonService {

    private final FollowedPersonRepository followedPersonRepository;
    private final UserRepository userRepository;
    private final FollowerRepository followerRepository;
    private final NewTransactionExecutor newTransactionExecutor;

    static final int DEFAULT_PAGE = 0;
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 1000;

    @Override
    public void followPerson(UUID userId, String personTmdbId) {
        validatePersonTmdbId(personTmdbId);

        if (followedPersonRepository.existsByUserIdAndPersonTmdbId(userId, personTmdbId)) {
            return;
        }

        try {
            newTransactionExecutor.runInNewTransaction(() -> {
                User user = userRepository.findById(userId)
                        .orElseThrow(() -> new NotFoundException("User not found"));
                FollowedPerson followedPerson = FollowedPerson.builder()
                        .user(user)
                        .personTmdbId(personTmdbId)
                        .createdAt(LocalDateTime.now())
                        .build();
                return followedPersonRepository.saveAndFlush(followedPerson);
            });
        } catch (DataIntegrityViolationException e) {
            if (!followedPersonRepository.existsByUserIdAndPersonTmdbId(userId, personTmdbId)) {
                throw e;
            }
        }
    }

    @Override
    public void unfollowPerson(UUID userId, String personTmdbId) {
        validatePersonTmdbId(personTmdbId);

        followedPersonRepository.findByUserIdAndPersonTmdbId(userId, personTmdbId)
                .ifPresent(followedPersonRepository::delete);
    }

    private void validatePersonTmdbId(String personTmdbId) {
        if (personTmdbId == null || !personTmdbId.matches("\\d{1,20}")) {
            throw new BadRequestException("personTmdbId must be a numeric TMDB id up to 20 digits");
        }
    }

    @Override
    public Page<String> getFollowedPeople(UUID viewerId, UUID targetUserId, Integer pageNumber, Integer pageSize) {
        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        assertCanViewFollowedPeople(viewerId, targetUserId, target);

        PageRequest pageRequest = buildPageRequest(pageNumber, pageSize);
        return followedPersonRepository.findByUserId(targetUserId, pageRequest)
                .map(FollowedPerson::getPersonTmdbId);
    }

    private void assertCanViewFollowedPeople(UUID viewerId, UUID targetUserId, User target) {
        if (Boolean.TRUE.equals(target.getIsProfilePublic()) || viewerId.equals(targetUserId)) {
            return;
        }

        boolean viewerFollowsTarget = followerRepository
                .existsByFollowerIdAndFollowedIdAndStatus(viewerId, targetUserId, FollowStatus.ACCEPTED);

        if (!viewerFollowsTarget) {
            throw new ForbiddenException("This user profile is private");
        }
    }

    public PageRequest buildPageRequest(Integer pageNumber, Integer pageSize) {
        int queryPageNumber;
        int queryPageSize;

        if (pageNumber != null && pageNumber > 0) {
            queryPageNumber = pageNumber - 1;
        } else if (pageNumber == null || pageNumber == 0) {
            queryPageNumber = DEFAULT_PAGE;
        } else {
            throw new BadRequestException("Page number must be greater than or equal to 0");
        }

        if (pageSize == null) {
            queryPageSize = DEFAULT_PAGE_SIZE;
        } else if (pageSize > MAX_PAGE_SIZE) {
            queryPageSize = MAX_PAGE_SIZE;
        } else if (pageSize <= 0) {
            throw new BadRequestException("Page size must be greater than 0");
        } else {
            queryPageSize = pageSize;
        }

        return PageRequest.of(queryPageNumber, queryPageSize);
    }

}