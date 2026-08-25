package com.watchwise.watchwise_api.follower.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.entity.Follower;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.follower.service.FollowerService;
import com.watchwise.watchwise_api.user.dto.PublicUserDTO;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
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
public class FollowerServiceImpl implements FollowerService {

    private final FollowerRepository followerRepository;
    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PageRequestFactory pageRequestFactory;

    @Override
    public FollowStatus followUser(UUID followerId, UUID followedId) {
        if (followerId.equals(followedId)) {
            throw new BadRequestException("Cannot follow yourself");
        }

        User followed = userRepository.findById(followedId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        User follower = userRepository.findById(followerId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        FollowStatus status = Boolean.TRUE.equals(followed.getIsProfilePublic())
                ? FollowStatus.ACCEPTED
                : FollowStatus.PENDING;

        Follower newFollow = Follower.builder()
                .follower(follower)
                .followed(followed)
                .status(status)
                .createdAt(LocalDateTime.now())
                .build();

        try {
            followerRepository.save(newFollow);
        } catch (DataIntegrityViolationException e) {
            throw mapUniqueConstraintViolation(e);
        }

        return status;
    }

    @Override
    public void unfollowUser(UUID followerId, UUID followedId) {
        Follower follow = followerRepository.findByFollowerIdAndFollowedId(followerId, followedId)
                .orElseThrow(() -> new NotFoundException("Follow relationship not found"));

        followerRepository.delete(follow);
    }

    @Override
    public void acceptFollowRequest(UUID targetUserId, UUID requesterId) {
        Follower follow = followerRepository
                .findByFollowerIdAndFollowedIdAndStatus(requesterId, targetUserId, FollowStatus.PENDING)
                .orElseThrow(() -> new NotFoundException("Follow request not found"));

        follow.setStatus(FollowStatus.ACCEPTED);
        followerRepository.save(follow);
    }

    @Override
    public void rejectFollowRequest(UUID targetUserId, UUID requesterId) {
        Follower follow = followerRepository
                .findByFollowerIdAndFollowedIdAndStatus(requesterId, targetUserId, FollowStatus.PENDING)
                .orElseThrow(() -> new NotFoundException("Follow request not found"));

        followerRepository.delete(follow);
    }

    @Override
    public Page<PublicUserDTO> getFollowers(UUID viewerId, UUID targetUserId, Integer pageNumber, Integer pageSize) {
        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        assertCanViewFollowGraph(viewerId, targetUserId, target);

        PageRequest pageRequest = pageRequestFactory.build(pageNumber, pageSize);
        return followerRepository.findByFollowedIdAndStatus(targetUserId, FollowStatus.ACCEPTED, pageRequest)
                .map(follow -> userMapper.userToPublicUserDto(follow.getFollower()));
    }

    @Override
    public Page<PublicUserDTO> getFollowing(UUID viewerId, UUID targetUserId, Integer pageNumber, Integer pageSize) {
        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        assertCanViewFollowGraph(viewerId, targetUserId, target);

        PageRequest pageRequest = pageRequestFactory.build(pageNumber, pageSize);
        return followerRepository.findByFollowerIdAndStatus(targetUserId, FollowStatus.ACCEPTED, pageRequest)
                .map(follow -> userMapper.userToPublicUserDto(follow.getFollowed()));
    }

    @Override
    public Page<PublicUserDTO> getPendingFollowRequests(UUID targetUserId, Integer pageNumber, Integer pageSize) {
        PageRequest pageRequest = pageRequestFactory.build(pageNumber, pageSize);
        return followerRepository.findByFollowedIdAndStatus(targetUserId, FollowStatus.PENDING, pageRequest)
                .map(follow -> userMapper.userToPublicUserDto(follow.getFollower()));
    }

    @Override
    public void acceptAllPendingFollowRequestsFor(UUID followedId) {
        followerRepository.acceptAllPendingFollowRequestsFor(followedId);
    }

    private void assertCanViewFollowGraph(UUID viewerId, UUID targetUserId, User target) {
        if (Boolean.TRUE.equals(target.getIsProfilePublic()) || viewerId.equals(targetUserId)) {
            return;
        }

        boolean viewerFollowsTarget = followerRepository
                .existsByFollowerIdAndFollowedIdAndStatus(viewerId, targetUserId, FollowStatus.ACCEPTED);

        if (!viewerFollowsTarget) {
            throw new ForbiddenException("This user profile is private");
        }
    }

    private ConflictException mapUniqueConstraintViolation(DataIntegrityViolationException e) {
        String constraintName = extractConstraintName(e);

        if ("uq_followers_follower_id_followed_id".equals(constraintName)) {
            return new ConflictException("Already following or already requested to follow this user");
        }
        return new ConflictException("Unable to follow this user");
    }

    private String extractConstraintName(DataIntegrityViolationException e) {
        Throwable cause = e.getCause();
        if (cause instanceof org.hibernate.exception.ConstraintViolationException cve) {
            return cve.getConstraintName();
        }
        return null;
    }
}