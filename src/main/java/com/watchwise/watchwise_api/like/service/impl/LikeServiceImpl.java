package com.watchwise.watchwise_api.like.service.impl;

import com.watchwise.watchwise_api.comment.entity.Comment;
import com.watchwise.watchwise_api.comment.repository.CommentRepository;
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
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LikeServiceImpl implements LikeService {

    private final LikeRepository likeRepository;
    private final UserRepository userRepository;
    private final CommentRepository commentRepository;
    private final DiaryEntryRepository diaryEntryRepository;
    private final FollowerRepository followerRepository;
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
                return likeRepository.saveAndFlush(like);
            });
        } catch (DataIntegrityViolationException e) {
            if (!likeRepository.existsByUserIdAndCommentId(userId, commentId)) {
                throw e;
            }
        }
    }

    @Override
    public void unlikeComment(UUID userId, UUID commentId) {
        likeRepository.findByUserIdAndCommentId(userId, commentId)
                .ifPresent(likeRepository::delete);
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
                return likeRepository.saveAndFlush(like);
            });
        } catch (DataIntegrityViolationException e) {
            if (!likeRepository.existsByUserIdAndDiaryEntryId(userId, diaryEntryId)) {
                throw e;
            }
        }
    }

    @Override
    public void unlikeDiaryEntry(UUID userId, UUID diaryEntryId) {
        likeRepository.findByUserIdAndDiaryEntryId(userId, diaryEntryId)
                .ifPresent(likeRepository::delete);
    }

    private void assertCommentIsVisibleTo(UUID viewerId, Comment comment) {
        if (comment.getContent() != null) {
            return;
        }

        if (comment.getList() != null) {
            assertListIsVisibleTo(viewerId, comment.getList());
            return;
        }

        assertDiaryEntryIsVisibleTo(viewerId, comment.getDiaryEntry());
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
