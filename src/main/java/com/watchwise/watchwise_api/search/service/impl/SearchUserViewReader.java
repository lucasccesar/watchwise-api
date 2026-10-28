package com.watchwise.watchwise_api.search.service.impl;

import com.watchwise.watchwise_api.common.search.SearchTextNormalizer;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.search.dto.SearchUserViewDTO;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userblock.repository.UserBlockRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SearchUserViewReader {

    private final UserRepository users;
    private final FollowerRepository followers;
    private final UserListRepository lists;
    private final DiaryEntryRepository diary;
    private final UserBlockRepository blocks;

    @Transactional(readOnly = true)
    public Page<SearchUserViewDTO> read(UUID viewerId, String query, Pageable pageable) {
        String escapedQuery = SearchTextNormalizer.escapeLikeWildcards(query);
        Page<User> page = users.searchByNameOrUsernameForView(query, escapedQuery, pageable);
        if (page.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, page.getTotalElements());
        }

        List<User> candidates = page.getContent();
        List<UUID> targetIds = candidates.stream().map(User::getId).toList();
        Set<UUID> blockedCounterpartIds = blocks.findBlockedCounterpartIdsEitherDirection(viewerId, targetIds);
        RelationshipState relationships = relationships(viewerId, targetIds);
        Set<UUID> visibleDetails = candidates.stream()
                .filter(user -> canViewDetails(viewerId, user, relationships.outgoingStatusByUserId()))
                .map(User::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        Map<UUID, Long> followersByUser = visibleDetails.isEmpty()
                ? Map.of()
                : countsByUserId(followers.countAcceptedFollowersByFollowedIdIn(
                        visibleDetails, FollowStatus.ACCEPTED));
        Map<UUID, Long> listsByUser = visibleDetails.isEmpty()
                ? Map.of()
                : lists.countListsVisibleToViewerByOwnerIdIn(viewerId, visibleDetails).stream()
                .collect(java.util.stream.Collectors.toMap(UserListRepository.OwnerListCount::getUserId,
                        UserListRepository.OwnerListCount::getCount));
        Map<UUID, Long> watchedByUser = visibleDetails.isEmpty()
                ? Map.of()
                : diary.countVisibleDiaryEntriesByUserIdIn(visibleDetails).stream()
                .collect(java.util.stream.Collectors.toMap(DiaryEntryRepository.UserDiaryCount::getUserId,
                        DiaryEntryRepository.UserDiaryCount::getCount));

        List<SearchUserViewDTO> cards = candidates.stream()
                .map(user -> toCard(viewerId, user, relationships, visibleDetails,
                        blockedCounterpartIds, followersByUser, listsByUser, watchedByUser))
                .toList();
        return new PageImpl<>(cards, pageable, page.getTotalElements());
    }

    private SearchUserViewDTO toCard(
            UUID viewerId,
            User user,
            RelationshipState relationships,
            Set<UUID> visibleDetails,
            Set<UUID> blockedCounterpartIds,
            Map<UUID, Long> followersByUser,
            Map<UUID, Long> listsByUser,
            Map<UUID, Long> watchedByUser) {
        UUID userId = user.getId();
        boolean ownedByViewer = viewerId.equals(userId);
        boolean detailsVisible = visibleDetails.contains(userId);
        return new SearchUserViewDTO(
                userId,
                user.getName(),
                user.getUsername(),
                user.getProfilePicture(),
                (ownedByViewer || !blockedCounterpartIds.contains(userId)) ? user.getDescription() : null,
                detailsVisible,
                detailsVisible ? watchedByUser.getOrDefault(userId, 0L) : null,
                detailsVisible ? listsByUser.getOrDefault(userId, 0L) : null,
                detailsVisible ? followersByUser.getOrDefault(userId, 0L) : null,
                ownedByViewer,
                ownedByViewer ? null : relationships.outgoingStatusByUserId().get(userId),
                !ownedByViewer && relationships.followsViewerIds().contains(userId));
    }

    private boolean canViewDetails(UUID viewerId, User target, Map<UUID, FollowStatus> outgoingStatuses) {
        return viewerId.equals(target.getId())
                || Boolean.TRUE.equals(target.getIsProfilePublic())
                || outgoingStatuses.get(target.getId()) == FollowStatus.ACCEPTED;
    }

    private RelationshipState relationships(UUID viewerId, Collection<UUID> targetIds) {
        Map<UUID, FollowStatus> outgoing = new HashMap<>();
        Set<UUID> followsViewer = new HashSet<>();
        for (FollowerRepository.ViewerRelationRow row : followers.findViewerRelations(viewerId, targetIds)) {
            if (viewerId.equals(row.getFollowerId())) {
                outgoing.put(row.getFollowedId(), row.getStatus());
            } else if (viewerId.equals(row.getFollowedId()) && row.getStatus() == FollowStatus.ACCEPTED) {
                followsViewer.add(row.getFollowerId());
            }
        }
        return new RelationshipState(outgoing, followsViewer);
    }

    private Map<UUID, Long> countsByUserId(List<FollowerRepository.UserCount> rows) {
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        return rows.stream().collect(java.util.stream.Collectors.toMap(
                FollowerRepository.UserCount::getUserId,
                FollowerRepository.UserCount::getCount));
    }

    private record RelationshipState(
            Map<UUID, FollowStatus> outgoingStatusByUserId,
            Set<UUID> followsViewerIds) {
    }
}
