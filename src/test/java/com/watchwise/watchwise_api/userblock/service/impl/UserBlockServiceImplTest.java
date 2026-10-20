package com.watchwise.watchwise_api.userblock.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.common.transaction.AdvisoryLock;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.notification.repository.NotificationRepository;
import com.watchwise.watchwise_api.userblock.dto.BlockedUserDTO;
import com.watchwise.watchwise_api.userblock.entity.UserBlock;
import com.watchwise.watchwise_api.userblock.repository.UserBlockRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserBlockServiceImplTest {
    @Mock UserBlockRepository userBlockRepository;
    @Mock UserRepository userRepository;
    @Mock UserMapper userMapper;
    @Mock FollowerRepository followerRepository;
    @Mock AdvisoryLock advisoryLock;
    @Mock NotificationRepository notificationRepository;
    @Mock PageRequestFactory pageRequestFactory;
    @InjectMocks UserBlockServiceImpl service;
    private final UUID blockerId = UUID.randomUUID();
    private final UUID blockedId = UUID.randomUUID();
    private final User blocker = user(blockerId, "blocker");
    private final User blocked = user(blockedId, "blocked");

    @Test void shouldCreateBlockAndRemoveFollowerRowsBothWays() {
        when(userRepository.findById(blockerId)).thenReturn(Optional.of(blocker));
        when(userRepository.findById(blockedId)).thenReturn(Optional.of(blocked));
        service.block(blockerId, blockedId);
        verify(userBlockRepository).saveAndFlush(any(UserBlock.class));
        verify(followerRepository).deleteByFollowerIdAndFollowedId(blockerId, blockedId);
        verify(followerRepository).deleteByFollowerIdAndFollowedId(blockedId, blockerId);
        verify(notificationRepository).deleteBetweenUsers(blockerId, blockedId);
    }

    @Test void shouldRejectSelfBlockBeforeWriting() {
        assertThatThrownBy(() -> service.block(blockerId, blockerId)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(userBlockRepository, followerRepository, advisoryLock);
    }

    @Test void shouldRejectMissingTarget() {
        when(userRepository.findById(blockedId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.block(blockerId, blockedId)).isInstanceOf(NotFoundException.class);
        verify(userBlockRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("[block] Should Skip Insert And Clean Followers - When Block Already Exists")
    void shouldSkipInsertAndCleanFollowersWhenBlockAlreadyExists() {
        when(userRepository.findById(blockedId)).thenReturn(Optional.of(blocked));
        when(userBlockRepository.existsByBlockerIdAndBlockedId(blockerId, blockedId))
                .thenReturn(true);
        service.block(blockerId, blockedId);
        verify(userBlockRepository, never()).saveAndFlush(any());
        verify(followerRepository).deleteByFollowerIdAndFollowedId(blockerId, blockedId);
        verify(followerRepository).deleteByFollowerIdAndFollowedId(blockedId, blockerId);
    }

    @Test void shouldUnblockOnlyMatchingDirectionIfPresent() {
        UserBlock row = UserBlock.builder().blocker(blocker).blocked(blocked).createdAt(LocalDateTime.now()).build();
        when(userBlockRepository.findByBlockerIdAndBlockedId(blockerId, blockedId)).thenReturn(Optional.of(row));
        service.unblock(blockerId, blockedId);
        verify(userBlockRepository).delete(row);
    }

    @Test void shouldReturnMappedBlockedUserPage() {
        BlockedUserDTO dto = mock(BlockedUserDTO.class);
        when(pageRequestFactory.build(1, 10)).thenReturn(PageRequest.of(0, 10));
        when(userBlockRepository.findByBlockerIdOrderByCreatedAtDescIdDesc(blockerId, PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of(UserBlock.builder().blocker(blocker).blocked(blocked).createdAt(LocalDateTime.now()).build())));
        when(userMapper.userToPublicUserDto(blocked)).thenReturn(null);
        var result = service.getBlockedUsers(blockerId, 1, 10);
        assertThat(result.getContent()).hasSize(1);
    }

    private static User user(UUID id, String username) {
        return User.builder().id(id).username(username).name(username).email(username + "@email.com")
                .password("hash").isProfilePublic(true).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }
}
