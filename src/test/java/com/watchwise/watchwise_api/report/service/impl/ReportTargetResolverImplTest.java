package com.watchwise.watchwise_api.report.service.impl;

import com.watchwise.watchwise_api.comment.entity.Comment;
import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.dropped.repository.DroppedEntryRepository;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.pick.repository.PickRepository;
import com.watchwise.watchwise_api.pick.repository.PickSelectionRepository;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateRepository;
import com.watchwise.watchwise_api.report.entity.ReportTargetType;
import com.watchwise.watchwise_api.report.service.ResolvedReportTarget;
import com.watchwise.watchwise_api.report.service.impl.ReportTargetResolverImpl;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.user.service.UserVisibilityService;
import com.watchwise.watchwise_api.userblock.service.UserBlockAccessService;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportTargetResolverImplTest {

    @Mock UserRepository userRepository;
    @Mock CommentRepository commentRepository;
    @Mock DiaryEntryRepository diaryEntryRepository;
    @Mock UserListRepository userListRepository;
    @Mock PickRepository pickRepository;
    @Mock PickSelectionRepository pickSelectionRepository;
    @Mock PicksTemplateRepository picksTemplateRepository;
    @Mock DroppedEntryRepository droppedEntryRepository;
    @Mock FollowerRepository followerRepository;
    @Mock UserBlockAccessService userBlockAccessService;
    @Mock UserVisibilityService userVisibilityService;
    @Spy ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks ReportTargetResolverImpl resolver;

    @Test
    void shouldResolveAndSnapshotACommentFromTheBlockedUser() {
        UUID reporterId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        User owner = user(UUID.randomUUID(), "blocked-author", true);
        Comment comment = Comment.builder().id(commentId).user(owner)
                .content(Content.builder().id(UUID.randomUUID()).type(ContentType.MOVIE).tmdbId("550").build())
                .text("reported text").containsSpoiler(true).createdAt(LocalDateTime.now()).build();
        when(commentRepository.findByIdWithTargets(commentId)).thenReturn(Optional.of(comment));
        when(userBlockAccessService.isBlockedEitherDirection(reporterId, owner.getId())).thenReturn(true);

        ResolvedReportTarget result = resolver.resolve(reporterId, ReportTargetType.COMMENT, commentId);

        assertThat(result.targetId()).isEqualTo(commentId);
        assertThat(result.ownerId()).isEqualTo(owner.getId());
        assertThat(result.snapshot()).contains("\"targetType\":\"COMMENT\"")
                .contains("\"text\":\"reported text\"")
                .contains("\"containsSpoiler\":true")
                .doesNotContain("email", "password", "profilePicture");
    }

    @Test
    void shouldHideAnInaccessiblePrivateReviewUnlessTheReporterBlockedItsOwner() {
        UUID reporterId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        User privateOwner = user(UUID.randomUUID(), "private-owner", false);
        DiaryEntry review = DiaryEntry.builder().id(reviewId).user(privateOwner)
                .content(Content.builder().id(UUID.randomUUID()).type(ContentType.MOVIE).tmdbId("603").build())
                .comment("review text").createdAt(LocalDateTime.now()).build();
        when(diaryEntryRepository.findByIdWithContentAndUser(reviewId)).thenReturn(Optional.of(review));
        when(userBlockAccessService.isBlockedEitherDirection(reporterId, privateOwner.getId())).thenReturn(false);
        doThrow(new com.watchwise.watchwise_api.common.exception.ForbiddenException("private"))
                .when(userVisibilityService).assertCanView(reporterId, privateOwner.getId());

        assertThatThrownBy(() -> resolver.resolve(reporterId, ReportTargetType.REVIEW, reviewId))
                .isInstanceOf(NotFoundException.class);
    }

    private static User user(UUID id, String username, boolean isPublic) {
        return User.builder().id(id).username(username).name(username).email(username + "@example.com")
                .password("secret-hash").profilePicture("https://example.com/photo.png")
                .isProfilePublic(isPublic).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }
}
