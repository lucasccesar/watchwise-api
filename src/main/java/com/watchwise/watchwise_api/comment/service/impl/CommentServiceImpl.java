package com.watchwise.watchwise_api.comment.service.impl;

import com.watchwise.watchwise_api.comment.dto.CommentCreationDTO;
import com.watchwise.watchwise_api.comment.dto.CommentResponseDTO;
import com.watchwise.watchwise_api.comment.entity.Comment;
import com.watchwise.watchwise_api.comment.mapper.CommentMapper;
import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.comment.service.CommentService;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.like.service.LikeService;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.userlist.repository.UserListItemRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CommentServiceImpl implements CommentService {

    private final CommentRepository commentRepository;
    private final UserRepository userRepository;
    private final ContentRepository contentRepository;
    private final UserListRepository userListRepository;
    private final UserListItemRepository userListItemRepository;
    private final DiaryEntryRepository diaryEntryRepository;
    private final FollowerRepository followerRepository;
    private final CommentMapper commentMapper;
    private final LikeService likeService;

    static final int DEFAULT_PAGE = 0;
    static final int DEFAULT_PAGE_SIZE = 20;

    @Override
    public Page<CommentResponseDTO> getCommentsForContent(UUID viewerId, UUID contentId, Integer pageNumber, Integer pageSize) {
        if (!contentRepository.existsById(contentId)) {
            throw new NotFoundException("Content not found");
        }

        PageRequest pageRequest = buildPageRequest(pageNumber, pageSize);

        Page<Comment> comments = commentRepository.findByContentIdOrderByCreatedAtAsc(contentId, pageRequest);
        return mapToResponseDtos(comments, viewerId);
    }

    @Override
    public Page<CommentResponseDTO> getCommentsForList(UUID viewerId, UUID listId, Integer pageNumber, Integer pageSize) {
        UserList list = userListRepository.findById(listId)
                .orElseThrow(() -> new NotFoundException("List not found"));

        assertListIsVisibleTo(viewerId, list);

        PageRequest pageRequest = buildPageRequest(pageNumber, pageSize);

        Page<Comment> comments = commentRepository.findByListIdOrderByCreatedAtAsc(listId, pageRequest);
        return mapToResponseDtos(comments, viewerId);
    }

    @Override
    public Page<CommentResponseDTO> getCommentsForDiaryEntry(UUID viewerId, UUID diaryEntryId, Integer pageNumber, Integer pageSize) {
        DiaryEntry diaryEntry = diaryEntryRepository.findByIdWithUser(diaryEntryId)
                .orElseThrow(() -> new NotFoundException("Diary entry not found"));

        assertDiaryEntryIsVisibleTo(viewerId, diaryEntry);

        PageRequest pageRequest = buildPageRequest(pageNumber, pageSize);

        Page<Comment> comments = commentRepository.findByDiaryEntryIdOrderByCreatedAtAsc(diaryEntryId, pageRequest);
        return mapToResponseDtos(comments, viewerId);
    }

    private Page<CommentResponseDTO> mapToResponseDtos(Page<Comment> comments, UUID viewerId) {
        List<UUID> commentIds = comments.getContent().stream().map(Comment::getId).toList();
        Set<UUID> likedCommentIds = likeService.getLikedCommentIds(viewerId, commentIds);

        return comments.map(comment -> commentMapper.commentToResponseDto(comment, likedCommentIds.contains(comment.getId())));
    }

    @Override
    @Transactional
    public CommentResponseDTO createCommentOnContent(UUID userId, UUID contentId, CommentCreationDTO commentCreationDTO) {
        Content content = contentRepository.findById(contentId)
                .orElseThrow(() -> new NotFoundException("Content not found"));

        Comment parentComment = resolveParentCommentOnContent(commentCreationDTO.parentCommentId(), contentId);

        Comment comment = baseCommentBuilder(userId, commentCreationDTO, parentComment)
                .content(content)
                .build();

        return commentMapper.commentToResponseDto(commentRepository.save(comment), false);
    }

    @Override
    @Transactional
    public CommentResponseDTO createCommentOnList(UUID userId, UUID listId, CommentCreationDTO commentCreationDTO) {
        UserList list = userListRepository.findById(listId)
                .orElseThrow(() -> new NotFoundException("List not found"));

        assertListIsVisibleTo(userId, list);
        assertListAcceptsComments(listId);

        Comment parentComment = resolveParentCommentOnList(commentCreationDTO.parentCommentId(), listId);

        Comment comment = baseCommentBuilder(userId, commentCreationDTO, parentComment)
                .list(list)
                .build();

        return commentMapper.commentToResponseDto(commentRepository.save(comment), false);
    }

    @Override
    @Transactional
    public CommentResponseDTO createCommentOnDiaryEntry(UUID userId, UUID diaryEntryId, CommentCreationDTO commentCreationDTO) {
        DiaryEntry diaryEntry = diaryEntryRepository.findByIdWithUser(diaryEntryId)
                .orElseThrow(() -> new NotFoundException("Diary entry not found"));

        assertDiaryEntryIsVisibleTo(userId, diaryEntry);

        Comment parentComment = resolveParentCommentOnDiaryEntry(commentCreationDTO.parentCommentId(), diaryEntryId);

        Comment comment = baseCommentBuilder(userId, commentCreationDTO, parentComment)
                .diaryEntry(diaryEntry)
                .build();

        return commentMapper.commentToResponseDto(commentRepository.save(comment), false);
    }

    @Override
    @Transactional
    public void deleteComment(UUID userId, UUID commentId) {
        Comment comment = findOwnedComment(userId, commentId);

        commentRepository.delete(comment);
    }

    private Comment findOwnedComment(UUID userId, UUID commentId) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new NotFoundException("Comment not found"));

        if (!comment.getUser().getId().equals(userId)) {
            throw new NotFoundException("Comment not found");
        }

        return comment;
    }

    private Comment.CommentBuilder baseCommentBuilder(UUID userId, CommentCreationDTO commentCreationDTO, Comment parentComment) {
        LocalDateTime now = LocalDateTime.now();
        return Comment.builder()
                .user(userRepository.getReferenceById(userId))
                .parentComment(parentComment)
                .text(commentCreationDTO.text())
                .containsSpoiler(commentCreationDTO.containsSpoiler() != null ? commentCreationDTO.containsSpoiler() : false)
                .createdAt(now)
                .updatedAt(now);
    }

    private Comment resolveParentCommentOnContent(UUID parentCommentId, UUID contentId) {
        if (parentCommentId == null) {
            return null;
        }

        Comment parent = findParentComment(parentCommentId);
        if (parent.getContent() == null || !parent.getContent().getId().equals(contentId)) {
            throw new BadRequestException("Parent comment must target the same content");
        }

        return parent;
    }

    private Comment resolveParentCommentOnList(UUID parentCommentId, UUID listId) {
        if (parentCommentId == null) {
            return null;
        }

        Comment parent = findParentComment(parentCommentId);
        if (parent.getList() == null || !parent.getList().getId().equals(listId)) {
            throw new BadRequestException("Parent comment must target the same list");
        }

        return parent;
    }

    private Comment resolveParentCommentOnDiaryEntry(UUID parentCommentId, UUID diaryEntryId) {
        if (parentCommentId == null) {
            return null;
        }

        Comment parent = findParentComment(parentCommentId);
        if (parent.getDiaryEntry() == null || !parent.getDiaryEntry().getId().equals(diaryEntryId)) {
            throw new BadRequestException("Parent comment must target the same diary entry");
        }

        return parent;
    }

    private Comment findParentComment(UUID parentCommentId) {
        return commentRepository.findById(parentCommentId)
                .orElseThrow(() -> new NotFoundException("Parent comment not found"));
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

    private void assertListAcceptsComments(UUID listId) {
        if (userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)) {
            throw new BadRequestException("This list is a list of lists and cannot receive comments");
        }
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

        if (pageSize == null || pageSize > 1000) {
            queryPageSize = DEFAULT_PAGE_SIZE;
        } else {
            if (pageSize <= 0) {
                throw new BadRequestException("Page size must be greater than 0");
            } else {
                queryPageSize = pageSize;
            }
        }

        return PageRequest.of(queryPageNumber, queryPageSize);
    }
}
