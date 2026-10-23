package com.watchwise.watchwise_api.report.service.impl;

import com.watchwise.watchwise_api.comment.entity.Comment;
import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.contentreview.repository.ContentReviewRepository;
import com.watchwise.watchwise_api.diarycompletion.repository.LegacyDiaryMigrationMapRepository;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.dropped.repository.DroppedEntryRepository;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.pick.entity.Pick;
import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pick.repository.PickRepository;
import com.watchwise.watchwise_api.pick.repository.PickSelectionRepository;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateRepository;
import com.watchwise.watchwise_api.report.entity.ReportTargetType;
import com.watchwise.watchwise_api.report.service.ResolvedReportTarget;
import com.watchwise.watchwise_api.report.service.impl.ReportTargetResolverImpl;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.user.service.UserVisibilityService;
import com.watchwise.watchwise_api.userblock.service.UserBlockAccessService;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportTargetResolverImplTest {

    @Mock UserRepository userRepository;
    @Mock CommentRepository commentRepository;
    @Mock DiaryEntryRepository diaryEntryRepository;
    @Mock ContentReviewRepository contentReviewRepository;
    @Mock LegacyDiaryMigrationMapRepository migrationMapRepository;
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

    @Test
    void allowsReportingAPrivateUserProfileByKnownId() {
        UUID reporterId = UUID.randomUUID();
        User privateOwner = user(UUID.randomUUID(), "private-profile", false);
        when(userRepository.findById(privateOwner.getId())).thenReturn(Optional.of(privateOwner));

        ResolvedReportTarget target = resolver.resolve(reporterId, ReportTargetType.USER, privateOwner.getId());

        assertThat(target.ownerId()).isEqualTo(privateOwner.getId());
        assertThat(target.snapshot()).contains("private-profile");
        verify(userVisibilityService, never()).assertCanView(reporterId, privateOwner.getId());
        verify(userBlockAccessService, never()).isBlockedEitherDirection(reporterId, privateOwner.getId());
    }

    @Test
    void doesNotAllowPickReportBecauseOnlyItsTemplateCreatorIsBlocked() {
        UUID reporterId = UUID.randomUUID();
        User owner = user(UUID.randomUUID(), "pick-owner", true);
        User templateCreator = user(UUID.randomUUID(), "blocked-template-creator", true);
        PicksTemplate template = PicksTemplate.builder().id(UUID.randomUUID()).creator(templateCreator)
                .name("Template").createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        Pick pick = Pick.builder().id(UUID.randomUUID()).user(owner).picksTemplate(template)
                .visibility(PickVisibility.PUBLIC).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(pickRepository.findByIdWithUserAndTemplateAndCreator(pick.getId())).thenReturn(Optional.of(pick));
        when(userBlockAccessService.isBlockedEitherDirection(reporterId, owner.getId())).thenReturn(false);
        when(userBlockAccessService.isBlockedEitherDirection(reporterId, templateCreator.getId())).thenReturn(true);

        assertThatThrownBy(() -> resolver.resolve(reporterId, ReportTargetType.PICK, pick.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void doesNotAllowReportingCommentInTemplateBlockedForReporterWhenItsAuthorIsNotBlocked() {
        UUID reporterId = UUID.randomUUID();
        User author = user(UUID.randomUUID(), "comment-author", true);
        User templateCreator = user(UUID.randomUUID(), "blocked-template-creator", true);
        PicksTemplate template = PicksTemplate.builder().id(UUID.randomUUID()).creator(templateCreator)
                .name("Template").createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        Comment comment = Comment.builder().id(UUID.randomUUID()).user(author).picksTemplate(template)
                .text("Comment by another user").createdAt(LocalDateTime.now()).build();
        when(commentRepository.findByIdWithTargets(comment.getId())).thenReturn(Optional.of(comment));
        when(userBlockAccessService.isBlockedEitherDirection(reporterId, author.getId())).thenReturn(false);
        when(userBlockAccessService.isBlockedEitherDirection(reporterId, templateCreator.getId())).thenReturn(true);

        assertThatThrownBy(() -> resolver.resolve(reporterId, ReportTargetType.COMMENT, comment.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void allowsReportingACommentOnTheReportersOwnPrivateList() {
        UUID reporterId = UUID.randomUUID();
        User reporter = user(reporterId, "reporter", false);
        User author = user(UUID.randomUUID(), "comment-author", true);
        UserList privateList = UserList.builder().id(UUID.randomUUID()).user(reporter)
                .name("Private list").visibility(UserListVisibility.PRIVATE)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        Comment comment = Comment.builder().id(UUID.randomUUID()).user(author).list(privateList)
                .text("Comment on my private list").createdAt(LocalDateTime.now()).build();
        when(commentRepository.findByIdWithTargets(comment.getId())).thenReturn(Optional.of(comment));
        when(userBlockAccessService.isBlockedEitherDirection(reporterId, author.getId())).thenReturn(false);

        ResolvedReportTarget target = resolver.resolve(reporterId, ReportTargetType.COMMENT, comment.getId());

        assertThat(target.targetId()).isEqualTo(comment.getId());
        assertThat(target.ownerId()).isEqualTo(author.getId());
    }

    @Test
    void allowsReportingACommentOnTheReportersOwnPrivatePick() {
        UUID reporterId = UUID.randomUUID();
        User reporter = user(reporterId, "reporter", true);
        User author = user(UUID.randomUUID(), "comment-author", true);
        PicksTemplate template = PicksTemplate.builder().id(UUID.randomUUID()).creator(marinaUser())
                .name("Visible template").createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        Pick privatePick = Pick.builder().id(UUID.randomUUID()).user(reporter).picksTemplate(template)
                .visibility(PickVisibility.PRIVATE).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        Comment comment = Comment.builder().id(UUID.randomUUID()).user(author).pick(privatePick)
                .text("Comment on my private pick").createdAt(LocalDateTime.now()).build();
        when(commentRepository.findByIdWithTargets(comment.getId())).thenReturn(Optional.of(comment));
        when(userBlockAccessService.isBlockedEitherDirection(reporterId, author.getId())).thenReturn(false);

        ResolvedReportTarget target = resolver.resolve(reporterId, ReportTargetType.COMMENT, comment.getId());

        assertThat(target.targetId()).isEqualTo(comment.getId());
        assertThat(target.ownerId()).isEqualTo(author.getId());
    }

    private User marinaUser() {
        return user(UUID.randomUUID(), "template-creator", true);
    }

    private static User user(UUID id, String username, boolean isPublic) {
        return User.builder().id(id).username(username).name(username).email(username + "@example.com")
                .password("secret-hash").profilePicture("https://example.com/photo.png")
                .isProfilePublic(isPublic).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }
}
