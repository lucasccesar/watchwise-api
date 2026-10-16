package com.watchwise.watchwise_api.settings.service.impl;

import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.entity.Follower;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.settings.dto.SettingsAccountDTO;
import com.watchwise.watchwise_api.settings.dto.SettingsContentPreferencesDTO;
import com.watchwise.watchwise_api.settings.dto.SettingsPrivacyDTO;
import com.watchwise.watchwise_api.settings.dto.SettingsProfileDTO;
import com.watchwise.watchwise_api.settings.dto.SettingsUserPreviewDTO;
import com.watchwise.watchwise_api.settings.dto.SettingsViewDTO;
import com.watchwise.watchwise_api.settings.service.SettingsViewService;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userblock.entity.UserBlock;
import com.watchwise.watchwise_api.userblock.repository.UserBlockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SettingsViewServiceImpl implements SettingsViewService {
    private final UserRepository userRepository;
    private final FollowerRepository followerRepository;
    private final UserBlockRepository userBlockRepository;

    @Override
    @Transactional(readOnly = true)
    public SettingsViewDTO getSettings(UUID currentUserId) {
        User user = userRepository.findById(currentUserId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        PageRequest previewPage = PageRequest.of(0, 10,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));

        long followersCount = followerRepository.countByFollowedIdAndStatus(currentUserId, FollowStatus.ACCEPTED);
        long followingCount = followerRepository.countByFollowerIdAndStatus(currentUserId, FollowStatus.ACCEPTED);
        long pendingCount = 0;
        List<SettingsUserPreviewDTO> pendingRequests = List.of();
        if (!user.getIsProfilePublic()) {
            Page<Follower> pending = followerRepository.findByFollowedIdAndStatus(
                    currentUserId, FollowStatus.PENDING, previewPage);
            pendingCount = pending.getTotalElements();
            pendingRequests = pending.getContent().stream().map(follow -> preview(follow.getFollower())).toList();
        }

        Page<UserBlock> blocks = userBlockRepository.findByBlockerIdOrderByCreatedAtDescIdDesc(currentUserId, previewPage);
        List<SettingsUserPreviewDTO> blockedUsers = blocks.getContent().stream()
                .map(block -> preview(block.getBlocked())).toList();

        return new SettingsViewDTO(
                new SettingsProfileDTO(user.getId(), user.getUsername(), user.getName(), user.getDescription(),
                        user.getProfilePicture(), user.getBanner(), user.getIsProfilePublic()),
                new SettingsPrivacyDTO(followersCount, followingCount, pendingCount, pendingRequests,
                        blocks.getTotalElements(), blockedUsers),
                new SettingsContentPreferencesDTO(user.getPreferredLanguage(), user.getPreferredRegion()),
                new SettingsAccountDTO(user.getEmail()));
    }

    private SettingsUserPreviewDTO preview(User user) {
        return new SettingsUserPreviewDTO(user.getId(), user.getUsername(), user.getName(), user.getProfilePicture());
    }
}
