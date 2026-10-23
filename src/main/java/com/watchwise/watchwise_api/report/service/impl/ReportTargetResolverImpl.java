package com.watchwise.watchwise_api.report.service.impl;

import com.watchwise.watchwise_api.comment.entity.Comment;
import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.contentreview.entity.ContentReview;
import com.watchwise.watchwise_api.contentreview.repository.ContentReviewRepository;
import com.watchwise.watchwise_api.diarycompletion.entity.LegacyDiaryMigrationMap;
import com.watchwise.watchwise_api.diarycompletion.repository.LegacyDiaryMigrationMapRepository;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
import com.watchwise.watchwise_api.dropped.repository.DroppedEntryRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.pick.entity.Pick;
import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pick.entity.PickSelection;
import com.watchwise.watchwise_api.pick.repository.PickRepository;
import com.watchwise.watchwise_api.pick.repository.PickSelectionRepository;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateRepository;
import com.watchwise.watchwise_api.report.entity.ReportTargetType;
import com.watchwise.watchwise_api.report.service.ReportTargetResolver;
import com.watchwise.watchwise_api.report.service.ResolvedReportTarget;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.user.service.UserVisibilityService;
import com.watchwise.watchwise_api.userblock.service.UserBlockAccessService;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReportTargetResolverImpl implements ReportTargetResolver {

    private final UserRepository userRepository;
    private final CommentRepository commentRepository;
    private final DiaryEntryRepository diaryEntryRepository;
    private final ContentReviewRepository contentReviewRepository;
    private final LegacyDiaryMigrationMapRepository migrationMapRepository;
    private final UserListRepository userListRepository;
    private final PickRepository pickRepository;
    private final PickSelectionRepository pickSelectionRepository;
    private final PicksTemplateRepository picksTemplateRepository;
    private final DroppedEntryRepository droppedEntryRepository;
    private final FollowerRepository followerRepository;
    private final UserBlockAccessService userBlockAccessService;
    private final UserVisibilityService userVisibilityService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public ResolvedReportTarget resolve(UUID reporterId, ReportTargetType targetType, UUID targetId) {
        if (reporterId == null || targetType == null || targetId == null) {
            throw new BadRequestException("Report target is required");
        }
        return switch (targetType) {
            case USER -> resolveUser(reporterId, targetId);
            case COMMENT -> resolveComment(reporterId, targetId);
            case REVIEW -> resolveReview(reporterId, targetId);
            case CONTENT_REVIEW -> resolveContentReview(reporterId, targetId, ReportTargetType.CONTENT_REVIEW, targetId);
            case USER_LIST -> resolveList(reporterId, targetId);
            case PICK -> resolvePick(reporterId, targetId);
            case PICKS_TEMPLATE -> resolveTemplate(reporterId, targetId);
            case DROPPED_ENTRY -> resolveDroppedEntry(reporterId, targetId);
        };
    }

    private ResolvedReportTarget resolveUser(UUID reporterId, UUID targetId) {
        User target = userRepository.findById(targetId)
                .orElseThrow(() -> new NotFoundException("Report target not found"));
        rejectSelfReport(reporterId, target.getId());

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("username", target.getUsername());
        evidence.put("name", target.getName());
        add(evidence, "description", target.getDescription());
        return snapshot(ReportTargetType.USER, target.getId(), target, evidence);
    }

    private ResolvedReportTarget resolveComment(UUID reporterId, UUID targetId) {
        Comment comment = commentRepository.findByIdWithTargets(targetId)
                .orElseThrow(() -> new NotFoundException("Report target not found"));
        User author = comment.getUser();
        rejectSelfReport(reporterId, author.getId());

        boolean authorBlocked = userBlockAccessService.isBlockedEitherDirection(reporterId, author.getId());
        if (!authorBlocked) {
            assertCommentContainerVisible(reporterId, comment);
        }

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("text", comment.getText());
        evidence.put("containsSpoiler", comment.getContainsSpoiler());
        evidence.put("createdAt", timestamp(comment.getCreatedAt()));
        if (comment.getContent() != null) {
            evidence.put("contentId", comment.getContent().getId());
        }
        add(evidence, "listId", comment.getList() == null ? null : comment.getList().getId());
        add(evidence, "diaryEntryId", comment.getDiaryEntry() == null ? null : comment.getDiaryEntry().getId());
        add(evidence, "contentReviewId", comment.getContentReview() == null ? null : comment.getContentReview().getId());
        add(evidence, "droppedEntryId", comment.getDroppedEntry() == null ? null : comment.getDroppedEntry().getId());
        add(evidence, "pickId", comment.getPick() == null ? null : comment.getPick().getId());
        add(evidence, "picksTemplateId", comment.getPicksTemplate() == null ? null : comment.getPicksTemplate().getId());
        add(evidence, "parentCommentId", comment.getParentComment() == null ? null : comment.getParentComment().getId());
        return snapshot(ReportTargetType.COMMENT, comment.getId(), author, evidence);
    }

    private ResolvedReportTarget resolveReview(UUID reporterId, UUID targetId) {
        DiaryEntry diaryEntry = diaryEntryRepository.findByIdWithContentAndUser(targetId).orElse(null);
        if (diaryEntry != null) {
            if (diaryEntry.getComment() == null) {
                throw new NotFoundException("Report target not found");
            }
            return resolveDiaryReview(reporterId, diaryEntry);
        }

        LegacyDiaryMigrationMap mapping = migrationMapRepository.findByOldDiaryEntryId(targetId).orElse(null);
        if (mapping == null || !"CONTENT_REVIEW".equals(mapping.getDestinationKind())) {
            throw new NotFoundException("Report target not found");
        }
        return resolveContentReview(reporterId, mapping.getDestinationId(), ReportTargetType.REVIEW, targetId);
    }

    private ResolvedReportTarget resolveDiaryReview(UUID reporterId, DiaryEntry review) {
        User author = review.getUser();
        rejectSelfReport(reporterId, author.getId());
        assertProfileTargetVisible(reporterId, author.getId());

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("text", review.getComment());
        add(evidence, "score", review.getScore());
        evidence.put("contentId", review.getContent().getId());
        evidence.put("createdAt", timestamp(review.getCreatedAt()));
        return snapshot(ReportTargetType.REVIEW, review.getId(), author, evidence);
    }

    private ResolvedReportTarget resolveContentReview(
            UUID reporterId, UUID contentReviewId, ReportTargetType snapshotType, UUID snapshotId) {
        ContentReview review = contentReviewRepository.findById(contentReviewId)
                .filter(candidate -> candidate.getComment() != null && !candidate.getComment().isBlank())
                .orElseThrow(() -> new NotFoundException("Report target not found"));
        User author = userRepository.findById(review.getUserId())
                .orElseThrow(() -> new NotFoundException("Report target not found"));
        rejectSelfReport(reporterId, author.getId());
        assertProfileTargetVisible(reporterId, author.getId());

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("text", review.getComment());
        add(evidence, "score", review.getScoreAtPublication());
        evidence.put("contentId", review.getContentId());
        evidence.put("createdAt", review.getCreatedAt() == null ? null : review.getCreatedAt().toString());
        if (snapshotType == ReportTargetType.REVIEW) {
            evidence.put("contentReviewId", review.getId());
        }
        return snapshot(snapshotType, snapshotId, author, evidence);
    }

    private ResolvedReportTarget resolveList(UUID reporterId, UUID targetId) {
        UserList list = userListRepository.findById(targetId)
                .orElseThrow(() -> new NotFoundException("Report target not found"));
        User owner = list.getUser();
        rejectSelfReport(reporterId, owner.getId());
        assertListTargetVisible(reporterId, list);

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("name", list.getName());
        add(evidence, "description", list.getDescription());
        evidence.put("visibility", list.getVisibility().name());
        evidence.put("createdAt", timestamp(list.getCreatedAt()));
        return snapshot(ReportTargetType.USER_LIST, list.getId(), owner, evidence);
    }

    private ResolvedReportTarget resolvePick(UUID reporterId, UUID targetId) {
        Pick pick = pickRepository.findByIdWithUserAndTemplateAndCreator(targetId)
                .orElseThrow(() -> new NotFoundException("Report target not found"));
        User owner = pick.getUser();
        rejectSelfReport(reporterId, owner.getId());
        assertPickTargetVisible(reporterId, pick);

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("visibility", pick.getVisibility().name());
        evidence.put("createdAt", timestamp(pick.getCreatedAt()));
        evidence.put("templateId", pick.getPicksTemplate().getId());
        evidence.put("templateName", pick.getPicksTemplate().getName());
        List<Map<String, Object>> selections = new ArrayList<>();
        for (PickSelection selection : pickSelectionRepository.findByPickIdIn(List.of(pick.getId()))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("categoryId", selection.getCategory().getId());
            item.put("categoryName", selection.getCategory().getName());
            add(item, "contentId", selection.getContent() == null ? null : selection.getContent().getId());
            add(item, "personTmdbId", selection.getPersonTmdbId());
            add(item, "contextContentId", selection.getContextContent() == null
                    ? null : selection.getContextContent().getId());
            selections.add(item);
        }
        evidence.put("selections", selections);
        return snapshot(ReportTargetType.PICK, pick.getId(), owner, evidence);
    }

    private ResolvedReportTarget resolveTemplate(UUID reporterId, UUID targetId) {
        PicksTemplate template = picksTemplateRepository.findById(targetId)
                .orElseThrow(() -> new NotFoundException("Report target not found"));
        User creator = template.getCreator();
        if (creator != null) {
            rejectSelfReport(reporterId, creator.getId());
        }

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("name", template.getName());
        add(evidence, "description", template.getDescription());
        add(evidence, "instructions", template.getInstructions());
        evidence.put("origin", template.getOrigin().name());
        add(evidence, "eligibilityStartDate", template.getEligibilityStartDate() == null
                ? null : template.getEligibilityStartDate().toString());
        add(evidence, "eligibilityEndDate", template.getEligibilityEndDate() == null
                ? null : template.getEligibilityEndDate().toString());
        evidence.put("createdAt", timestamp(template.getCreatedAt()));
        return snapshot(ReportTargetType.PICKS_TEMPLATE, template.getId(), creator, evidence);
    }

    private ResolvedReportTarget resolveDroppedEntry(UUID reporterId, UUID targetId) {
        DroppedEntry dropped = droppedEntryRepository.findByIdWithUserAndContent(targetId)
                .orElseThrow(() -> new NotFoundException("Report target not found"));
        User owner = dropped.getUser();
        rejectSelfReport(reporterId, owner.getId());
        assertProfileTargetVisible(reporterId, owner.getId());

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("type", dropped.getType().name());
        add(evidence, "text", dropped.getComment());
        evidence.put("contentId", dropped.getContent().getId());
        evidence.put("createdAt", timestamp(dropped.getCreatedAt()));
        return snapshot(ReportTargetType.DROPPED_ENTRY, dropped.getId(), owner, evidence);
    }

    private void assertCommentContainerVisible(UUID reporterId, Comment comment) {
        if (comment.getContent() != null) {
            return;
        }
        if (comment.getList() != null) {
            assertListContainerVisible(reporterId, comment.getList());
            return;
        }
        if (comment.getDiaryEntry() != null) {
            assertProfileContainerVisible(reporterId, comment.getDiaryEntry().getUser().getId());
            return;
        }
        if (comment.getContentReview() != null) {
            assertProfileContainerVisible(reporterId, comment.getContentReview().getUserId());
            return;
        }
        if (comment.getDroppedEntry() != null) {
            assertProfileContainerVisible(reporterId, comment.getDroppedEntry().getUser().getId());
            return;
        }
        if (comment.getPick() != null) {
            assertPickContainerVisible(reporterId, comment.getPick());
            return;
        }
        if (comment.getPicksTemplate() != null) {
            User creator = comment.getPicksTemplate().getCreator();
            if (creator != null && userBlockAccessService.isBlockedEitherDirection(reporterId, creator.getId())) {
                throw new NotFoundException("Report target not found");
            }
            return;
        }
        throw new NotFoundException("Report target not found");
    }

    private void assertProfileTargetVisible(UUID reporterId, UUID ownerId) {
        if (reporterId.equals(ownerId) || userBlockAccessService.isBlockedEitherDirection(reporterId, ownerId)) {
            return;
        }
        try {
            userVisibilityService.assertCanView(reporterId, ownerId);
        } catch (ForbiddenException inaccessible) {
            throw new NotFoundException("Report target not found");
        }
    }

    private void assertProfileContainerVisible(UUID reporterId, UUID ownerId) {
        if (!reporterId.equals(ownerId) && userBlockAccessService.isBlockedEitherDirection(reporterId, ownerId)) {
            throw new NotFoundException("Report target not found");
        }
        try {
            userVisibilityService.assertCanView(reporterId, ownerId);
        } catch (ForbiddenException inaccessible) {
            throw new NotFoundException("Report target not found");
        }
    }

    private void assertListTargetVisible(UUID reporterId, UserList list) {
        UUID ownerId = list.getUser().getId();
        if (reporterId.equals(ownerId) || userBlockAccessService.isBlockedEitherDirection(reporterId, ownerId)) {
            return;
        }
        if (list.getVisibility() == UserListVisibility.PUBLIC
                || (list.getVisibility() == UserListVisibility.FOLLOWERS
                    && followerRepository.existsByFollowerIdAndFollowedIdAndStatus(
                            reporterId, ownerId, FollowStatus.ACCEPTED))) {
            return;
        }
        throw new NotFoundException("Report target not found");
    }

    private void assertListContainerVisible(UUID reporterId, UserList list) {
        UUID ownerId = list.getUser().getId();
        if (reporterId.equals(ownerId)) {
            return;
        }
        if (userBlockAccessService.isBlockedEitherDirection(reporterId, ownerId)) {
            throw new NotFoundException("Report target not found");
        }
        if (list.getVisibility() == UserListVisibility.PUBLIC
                || (list.getVisibility() == UserListVisibility.FOLLOWERS
                    && followerRepository.existsByFollowerIdAndFollowedIdAndStatus(
                            reporterId, ownerId, FollowStatus.ACCEPTED))) {
            return;
        }
        throw new NotFoundException("Report target not found");
    }

    private void assertPickTargetVisible(UUID reporterId, Pick pick) {
        UUID ownerId = pick.getUser().getId();
        if (reporterId.equals(ownerId) || userBlockAccessService.isBlockedEitherDirection(reporterId, ownerId)) {
            return;
        }
        User templateCreator = pick.getPicksTemplate().getCreator();
        if (templateCreator != null
                && userBlockAccessService.isBlockedEitherDirection(reporterId, templateCreator.getId())) {
            throw new NotFoundException("Report target not found");
        }
        if (pick.getVisibility() == PickVisibility.PUBLIC
                || (pick.getVisibility() == PickVisibility.FOLLOWERS
                    && followerRepository.existsByFollowerIdAndFollowedIdAndStatus(
                            reporterId, ownerId, FollowStatus.ACCEPTED))) {
            return;
        }
        throw new NotFoundException("Report target not found");
    }

    private void assertPickContainerVisible(UUID reporterId, Pick pick) {
        UUID ownerId = pick.getUser().getId();
        if (!reporterId.equals(ownerId) && userBlockAccessService.isBlockedEitherDirection(reporterId, ownerId)) {
            throw new NotFoundException("Report target not found");
        }
        User templateCreator = pick.getPicksTemplate().getCreator();
        if (templateCreator != null
                && userBlockAccessService.isBlockedEitherDirection(reporterId, templateCreator.getId())) {
            throw new NotFoundException("Report target not found");
        }
        if (reporterId.equals(ownerId)) {
            return;
        }
        if (pick.getVisibility() == PickVisibility.PUBLIC
                || (pick.getVisibility() == PickVisibility.FOLLOWERS
                    && followerRepository.existsByFollowerIdAndFollowedIdAndStatus(
                            reporterId, ownerId, FollowStatus.ACCEPTED))) {
            return;
        }
        throw new NotFoundException("Report target not found");
    }

    private void rejectSelfReport(UUID reporterId, UUID ownerId) {
        if (reporterId.equals(ownerId)) {
            throw new BadRequestException("You cannot report your own content");
        }
    }

    private ResolvedReportTarget snapshot(
            ReportTargetType targetType, UUID targetId, User owner, Map<String, Object> evidence) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("targetType", targetType.name());
        snapshot.put("targetId", targetId);
        if (owner != null) {
            Map<String, Object> ownerPreview = new LinkedHashMap<>();
            ownerPreview.put("id", owner.getId());
            ownerPreview.put("username", owner.getUsername());
            ownerPreview.put("name", owner.getName());
            snapshot.put("owner", ownerPreview);
        } else {
            snapshot.put("owner", null);
        }
        snapshot.put("capturedAt", LocalDateTime.now().toString());
        snapshot.put("evidence", evidence);
        try {
            return new ResolvedReportTarget(targetId, owner == null ? null : owner.getId(),
                    objectMapper.writeValueAsString(snapshot));
        } catch (Exception serializationFailure) {
            throw new IllegalStateException("Could not create report snapshot", serializationFailure);
        }
    }

    private static void add(Map<String, Object> values, String key, Object value) {
        if (value != null) {
            values.put(key, value);
        }
    }

    private static String timestamp(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

}
