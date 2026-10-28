package com.watchwise.watchwise_api.userblock.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.common.transaction.AdvisoryLock;
import com.watchwise.watchwise_api.common.transaction.UserPairLockIdentity;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.notification.repository.NotificationRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.dto.PublicUserDTO;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userblock.dto.BlockedUserDTO;
import com.watchwise.watchwise_api.userblock.entity.UserBlock;
import com.watchwise.watchwise_api.userblock.repository.UserBlockRepository;
import com.watchwise.watchwise_api.userblock.service.UserBlockService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserBlockServiceImpl implements UserBlockService {

    private final UserBlockRepository userBlockRepository;
    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final FollowerRepository followerRepository;
    private final AdvisoryLock advisoryLock;
    private final PageRequestFactory pageRequestFactory;
    private final NotificationRepository notificationRepository;

    @Override
    @Transactional
    public void block(UUID blockerId, UUID blockedId) {
        if (blockerId.equals(blockedId)) {
            throw new BadRequestException("A user cannot block themselves");
        }
        advisoryLock.lock(UserPairLockIdentity.of(blockerId, blockedId));
        User blocked = userRepository.findById(blockedId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        if (!userBlockRepository.existsByBlockerIdAndBlockedId(blockerId, blockedId)) {
            User blocker = userRepository.findById(blockerId)
                    .orElseThrow(() -> new NotFoundException("User not found"));
            userBlockRepository.saveAndFlush(UserBlock.builder()
                    .blocker(blocker)
                    .blocked(blocked)
                    .createdAt(LocalDateTime.now())
                    .build());
        }

        followerRepository.deleteByFollowerIdAndFollowedId(blockerId, blockedId);
        followerRepository.deleteByFollowerIdAndFollowedId(blockedId, blockerId);
        notificationRepository.deleteBetweenUsers(blockerId, blockedId);
    }

    @Override
    @Transactional
    public void unblock(UUID blockerId, UUID blockedId) {
        advisoryLock.lock(UserPairLockIdentity.of(blockerId, blockedId));
        userBlockRepository.findByBlockerIdAndBlockedId(blockerId, blockedId)
                .ifPresent(userBlockRepository::delete);
    }

    @Override
    public Page<BlockedUserDTO> getBlockedUsers(UUID blockerId, Integer page, Integer size) {
        return userBlockRepository.findByBlockerIdOrderByCreatedAtDescIdDesc(
                        blockerId, pageRequestFactory.build(page, size))
                .map(block -> new BlockedUserDTO(withoutBiography(userMapper.userToPublicUserDto(block.getBlocked())),
                        block.getCreatedAt()));
    }

    private PublicUserDTO withoutBiography(PublicUserDTO user) {
        return new PublicUserDTO(user.id(), user.username(), user.name(), null, user.profilePicture(),
                user.isProfilePublic(), user.createdAt());
    }
}
