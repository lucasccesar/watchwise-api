package com.watchwise.watchwise_api.like.service.impl;

import com.watchwise.watchwise_api.comment.entity.Comment;
import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.like.entity.Like;
import com.watchwise.watchwise_api.like.repository.LikeRepository;
import com.watchwise.watchwise_api.like.service.LikeService;
import com.watchwise.watchwise_api.pick.entity.Pick;
import com.watchwise.watchwise_api.pick.repository.PickRepository;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateRepository;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.userlist.repository.UserListItemRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LikeServiceImpl implements LikeService {

    private final LikeRepository likeRepository;
    private final UserRepository userRepository;
    private final CommentRepository commentRepository;
    private final DiaryEntryRepository diaryEntryRepository;
    private final UserListRepository userListRepository;
    private final UserListItemRepository userListItemRepository;
    private final FollowerRepository followerRepository;
    private final PickRepository pickRepository;
    private final PicksTemplateRepository picksTemplateRepository;
    private final NewTransactionExecutor newTransactionExecutor;

    @Override
    public void likeComment(UUID userId, UUID commentId) {
        if (likeRepository.existsByUserIdAndCommentId(userId, commentId)) {
            return;
        }

        Comment comment = commentRepository.findByIdWithTargets(commentId)
                .orElseThrow(() -> new NotFoundException("Comment not found"));

        assertCommentIsVisibleTo(userId, comment);

        try {
            newTransactionExecutor.runInNewTransaction(() -> {
                Like like = Like.builder()
                        .user(userRepository.getReferenceById(userId))
                        .comment(commentRepository.getReferenceById(commentId))
                        .createdAt(LocalDateTime.now())
                        .build();
                Like saved = likeRepository.saveAndFlush(like);
                commentRepository.incrementLikesCount(commentId);
                return saved;
            });
        } catch (DataIntegrityViolationException e) {
            if (!likeRepository.existsByUserIdAndCommentId(userId, commentId)) {
                throw e;
            }
        }
    }

    @Override
    @Transactional
    public void unlikeComment(UUID userId, UUID commentId) {
        if (likeRepository.deleteByUserIdAndCommentId(userId, commentId) > 0) {
            commentRepository.decrementLikesCount(commentId);
        }
    }

    @Override
    public void likeDiaryEntry(UUID userId, UUID diaryEntryId) {
        if (likeRepository.existsByUserIdAndDiaryEntryId(userId, diaryEntryId)) {
            return;
        }

        DiaryEntry diaryEntry = diaryEntryRepository.findByIdWithUser(diaryEntryId)
                .orElseThrow(() -> new NotFoundException("Diary entry not found"));

        assertDiaryEntryIsVisibleTo(userId, diaryEntry);

        try {
            newTransactionExecutor.runInNewTransaction(() -> {
                Like like = Like.builder()
                        .user(userRepository.getReferenceById(userId))
                        .diaryEntry(diaryEntryRepository.getReferenceById(diaryEntryId))
                        .createdAt(LocalDateTime.now())
                        .build();
                Like saved = likeRepository.saveAndFlush(like);
                diaryEntryRepository.incrementLikesCount(diaryEntryId);
                return saved;
            });
        } catch (DataIntegrityViolationException e) {
            if (!likeRepository.existsByUserIdAndDiaryEntryId(userId, diaryEntryId)) {
                throw e;
            }
        }
    }

    @Override
    @Transactional
    public void unlikeDiaryEntry(UUID userId, UUID diaryEntryId) {
        if (likeRepository.deleteByUserIdAndDiaryEntryId(userId, diaryEntryId) > 0) {
            diaryEntryRepository.decrementLikesCount(diaryEntryId);
        }
    }

    @Override
    public void likeList(UUID userId, UUID listId) {
        if (likeRepository.existsByUserIdAndListId(userId, listId)) {
            return;
        }

        UserList list = userListRepository.findById(listId)
                .orElseThrow(() -> new NotFoundException("List not found"));

        assertListIsVisibleTo(userId, list);
        assertListAcceptsLikes(listId);

        try {
            newTransactionExecutor.runInNewTransaction(() -> {
                Like like = Like.builder()
                        .user(userRepository.getReferenceById(userId))
                        .list(userListRepository.getReferenceById(listId))
                        .createdAt(LocalDateTime.now())
                        .build();
                Like saved = likeRepository.saveAndFlush(like);
                userListRepository.incrementLikesCount(listId);
                return saved;
            });
        } catch (DataIntegrityViolationException e) {
            if (!likeRepository.existsByUserIdAndListId(userId, listId)) {
                throw e;
            }
        }
    }

    @Override
    @Transactional
    public void unlikeList(UUID userId, UUID listId) {
        if (likeRepository.deleteByUserIdAndListId(userId, listId) > 0) {
            userListRepository.decrementLikesCount(listId);
        }
    }

    @Override
    public void likePick(UUID userId, UUID pickId) {
        if (likeRepository.existsByUserIdAndPickId(userId, pickId)) {
            return;
        }

        Pick pick = pickRepository.findById(pickId)
                .orElseThrow(() -> new NotFoundException("Pick not found"));
        assertPickIsVisibleTo(userId, pick);

        try {
            newTransactionExecutor.runInNewTransaction(() -> {
                Pick lockedPick = pickRepository.findByIdForUpdate(pickId)
                        .orElseThrow(() -> new NotFoundException("Pick not found"));
                Like like = Like.builder()
                        .user(userRepository.getReferenceById(userId))
                        .pick(lockedPick)
                        .createdAt(LocalDateTime.now())
                        .build();
                Like saved = likeRepository.saveAndFlush(like);
                lockedPick.setLikesCount((lockedPick.getLikesCount() == null ? 0 : lockedPick.getLikesCount()) + 1);
                return saved;
            });
        } catch (DataIntegrityViolationException e) {
            if (!likeRepository.existsByUserIdAndPickId(userId, pickId)) {
                throw e;
            }
        }
    }

    @Override
    @Transactional
    public void unlikePick(UUID userId, UUID pickId) {
        if (likeRepository.deleteByUserIdAndPickId(userId, pickId) > 0) {
            Pick pick = pickRepository.findByIdForUpdate(pickId)
                    .orElseThrow(() -> new NotFoundException("Pick not found"));
            pick.setLikesCount(Math.max(0, pick.getLikesCount() == null ? 0 : pick.getLikesCount() - 1));
        }
    }

    @Override
    public void likePicksTemplate(UUID userId, UUID templateId) {
        if (likeRepository.existsByUserIdAndPicksTemplateId(userId, templateId)) {
            return;
        }

        picksTemplateRepository.findById(templateId)
                .orElseThrow(() -> new NotFoundException("Picks template not found"));

        try {
            newTransactionExecutor.runInNewTransaction(() -> {
                PicksTemplate lockedTemplate = picksTemplateRepository.findByIdForUpdate(templateId)
                        .orElseThrow(() -> new NotFoundException("Picks template not found"));
                Like like = Like.builder()
                        .user(userRepository.getReferenceById(userId))
                        .picksTemplate(lockedTemplate)
                        .createdAt(LocalDateTime.now())
                        .build();
                Like saved = likeRepository.saveAndFlush(like);
                lockedTemplate.setLikesCount((lockedTemplate.getLikesCount() == null ? 0 : lockedTemplate.getLikesCount()) + 1);
                return saved;
            });
        } catch (DataIntegrityViolationException e) {
            if (!likeRepository.existsByUserIdAndPicksTemplateId(userId, templateId)) {
                throw e;
            }
        }
    }

    @Override
    @Transactional
    public void unlikePicksTemplate(UUID userId, UUID templateId) {
        if (likeRepository.deleteByUserIdAndPicksTemplateId(userId, templateId) > 0) {
            PicksTemplate template = picksTemplateRepository.findByIdForUpdate(templateId)
                    .orElseThrow(() -> new NotFoundException("Picks template not found"));
            template.setLikesCount(Math.max(0, template.getLikesCount() == null ? 0 : template.getLikesCount() - 1));
        }
    }

    @Override
    public Set<UUID> getLikedCommentIds(UUID userId, Collection<UUID> commentIds) {
        if (commentIds.isEmpty()) {
            return Set.of();
        }
        return likeRepository.findLikedCommentIds(userId, commentIds);
    }

    @Override
    public Set<UUID> getLikedDiaryEntryIds(UUID userId, Collection<UUID> diaryEntryIds) {
        if (diaryEntryIds.isEmpty()) {
            return Set.of();
        }
        return likeRepository.findLikedDiaryEntryIds(userId, diaryEntryIds);
    }

    @Override
    public Set<UUID> getLikedListIds(UUID userId, Collection<UUID> listIds) {
        if (listIds.isEmpty()) {
            return Set.of();
        }
        return likeRepository.findLikedListIds(userId, listIds);
    }

    @Override
    public Set<UUID> getLikedPickIds(UUID userId, Collection<UUID> pickIds) {
        if (pickIds.isEmpty()) {
            return Set.of();
        }
        return likeRepository.findLikedPickIds(userId, pickIds);
    }

    @Override
    public Set<UUID> getLikedPicksTemplateIds(UUID userId, Collection<UUID> templateIds) {
        if (templateIds.isEmpty()) {
            return Set.of();
        }
        return likeRepository.findLikedPicksTemplateIds(userId, templateIds);
    }

    private void assertListAcceptsLikes(UUID listId) {
        if (userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)) {
            throw new BadRequestException("This list is a list of lists and cannot receive likes");
        }
    }

    private void assertCommentIsVisibleTo(UUID viewerId, Comment comment) {
        if (comment.getContent() != null) {
            return;
        }

        if (comment.getList() != null) {
            assertListIsVisibleTo(viewerId, comment.getList());
            return;
        }

        if (comment.getPick() != null) {
            assertPickIsVisibleTo(viewerId, comment.getPick());
            return;
        }

        if (comment.getPicksTemplate() != null) {
            return;
        }

        assertDiaryEntryIsVisibleTo(viewerId, comment.getDiaryEntry());
    }

    private void assertPickIsVisibleTo(UUID viewerId, Pick pick) {
        UUID ownerId = pick.getUser().getId();
        if (viewerId.equals(ownerId) || pick.getVisibility() == com.watchwise.watchwise_api.pick.entity.PickVisibility.PUBLIC) {
            return;
        }
        if (pick.getVisibility() == com.watchwise.watchwise_api.pick.entity.PickVisibility.FOLLOWERS
                && followerRepository.existsByFollowerIdAndFollowedIdAndStatus(viewerId, ownerId, FollowStatus.ACCEPTED)) {
            return;
        }
        throw new ForbiddenException("This Pick is private");
    }

    private void assertListIsVisibleTo(UUID viewerId, UserList list) {
        UUID ownerId = list.getUser().getId();

        if (viewerId.equals(ownerId) || list.getVisibility() == UserListVisibility.PUBLIC) {
            return;
        }

        if (list.getVisibility() == UserListVisibility.FOLLOWERS
                && followerRepository.existsByFollowerIdAndFollowedIdAndStatus(viewerId, ownerId, FollowStatus.ACCEPTED)) {
            return;
        }

        throw new ForbiddenException("This list is private");
    }

    private void assertDiaryEntryIsVisibleTo(UUID viewerId, DiaryEntry diaryEntry) {
        UUID ownerId = diaryEntry.getUser().getId();

        if (viewerId.equals(ownerId) || Boolean.TRUE.equals(diaryEntry.getUser().getIsProfilePublic())) {
            return;
        }

        if (followerRepository.existsByFollowerIdAndFollowedIdAndStatus(viewerId, ownerId, FollowStatus.ACCEPTED)) {
            return;
        }

        throw new ForbiddenException("This diary entry is private");
    }
}
