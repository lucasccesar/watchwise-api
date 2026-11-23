package com.watchwise.watchwise_api.user.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.user.dto.ProfileHeaderDTO;
import com.watchwise.watchwise_api.user.dto.ProfileHeaderDTO.Access;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userblock.entity.UserBlockRelationship;
import com.watchwise.watchwise_api.userblock.service.UserBlockAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProfileHeaderReader {
    private final UserRepository users;
    private final FollowerRepository followers;
    private final DiaryEntryRepository diary;
    private final UserBlockAccessService blocks;

    @Transactional(readOnly = true)
    public ProfileHeaderDTO read(UUID viewerId, String requestedUsername) {
        String username = exactUsername(requestedUsername);
        var target = users.findProfileHeaderIdentityByUsername(username)
                .filter(row -> row.getUsername().toLowerCase(Locale.ROOT).equals(username.toLowerCase(Locale.ROOT)))
                .orElseThrow(() -> new NotFoundException("User not found"));
        UUID targetId = target.getId();
        boolean own = viewerId.equals(targetId);
        boolean isPublic = Boolean.TRUE.equals(target.getIsProfilePublic());
        var block = own ? UserBlockRelationship.NONE : blocks.relationship(viewerId, targetId);

        List<FollowerRepository.ViewerRelationRow> relations = own || (block != UserBlockRelationship.NONE && isPublic)
                ? List.of()
                : followers.findViewerRelations(viewerId, List.of(targetId));
        FollowStatus outgoing = relations.stream()
                .filter(row -> viewerId.equals(row.getFollowerId()))
                .map(FollowerRepository.ViewerRelationRow::getStatus)
                .findFirst().orElse(null);
        boolean eligible = own || isPublic || outgoing == FollowStatus.ACCEPTED;
        if (block != UserBlockRelationship.NONE) {
            // Preserve the overview's unavailable or private view without requesting protected sections.
            Access view = eligible ? Access.UNAVAILABLE : Access.PRIVATE;
            return new ProfileHeaderDTO(targetId, target.getUsername(), target.getName(), view,
                    null, null, null, null, null, null, null, null, null, null, null, block);
        }

        boolean followsViewer = relations.stream().anyMatch(row ->
                targetId.equals(row.getFollowerId()) && row.getStatus() == FollowStatus.ACCEPTED);
        long followersCount = followers.countByFollowedIdAndStatus(targetId, FollowStatus.ACCEPTED);
        long followingCount = followers.countByFollowerIdAndStatus(targetId, FollowStatus.ACCEPTED);
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(UserServiceImpl.WATCH_TIME_WINDOW_DAYS);
        Long movieMinutes = eligible ? recentMinutes(targetId, ContentType.MOVIE, start, end) : null;
        Long episodeMinutes = eligible ? recentMinutes(targetId, ContentType.EPISODE, start, end) : null;
        return new ProfileHeaderDTO(targetId, target.getUsername(), target.getName(),
                eligible ? Access.VISIBLE : Access.PRIVATE, target.getDescription(), target.getProfilePicture(),
                target.getBanner(), isPublic, followersCount, followingCount, movieMinutes, episodeMinutes,
                own, outgoing, followsViewer, block);
    }

    private String exactUsername(String input) {
        String username = input.startsWith("@") ? input.substring(1) : input;
        String trimmed = username.trim();
        if (trimmed.length() < 3 || trimmed.length() > 100) {
            throw new BadRequestException("Invalid profile username length");
        }
        if (!username.equals(trimmed)) {
            throw new NotFoundException("User not found");
        }
        return username;
    }

    private long recentMinutes(UUID userId, ContentType type, LocalDate start, LocalDate end) {
        return diary.sumRuntimeMinutesByUserIdAndContentTypeAndWatchedDateBetween(userId, type, start, end);
    }
}

