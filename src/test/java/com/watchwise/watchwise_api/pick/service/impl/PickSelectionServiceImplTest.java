package com.watchwise.watchwise_api.pick.service.impl;

import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.pick.dto.*;
import com.watchwise.watchwise_api.pick.entity.Pick;
import com.watchwise.watchwise_api.pick.entity.PickSelection;
import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pick.mapper.PickMapper;
import com.watchwise.watchwise_api.pick.repository.PickRepository;
import com.watchwise.watchwise_api.pick.repository.PickSelectionRepository;
import com.watchwise.watchwise_api.pick.service.PickSelectionService;
import com.watchwise.watchwise_api.pick.service.PickTargetService;
import com.watchwise.watchwise_api.pick.service.ResolvedPickTarget;
import com.watchwise.watchwise_api.pick.service.PreparedPickTarget;
import com.watchwise.watchwise_api.pick.service.PreparedTargetValidation;
import com.watchwise.watchwise_api.like.service.LikeService;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplatePreviewDTO;
import com.watchwise.watchwise_api.pickstemplate.entity.*;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateCategoryRepository;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateRepository;
import com.watchwise.watchwise_api.pickstemplate.service.impl.PicksTemplatePreviewAssembler;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userblock.service.UserBlockAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PickSelectionServiceImplTest {
    @Mock PickRepository pickRepository;
    @Mock PickSelectionRepository selectionRepository;
    @Mock PicksTemplateRepository templateRepository;
    @Mock PicksTemplateCategoryRepository categoryRepository;
    @Mock UserRepository userRepository;
    @Mock FollowerRepository followerRepository;
    @Mock PickTargetService targetService;
    @Mock PickMapper pickMapper;
    @Mock PicksTemplatePreviewAssembler templatePreviewAssembler;
    @Mock CommentRepository commentRepository;
    @Mock LikeService likeService;
    @Mock UserBlockAccessService userBlockAccessService;

    PickSelectionService service;
    UUID ownerId;
    PicksTemplate template;
    PicksTemplateCategory movieCategory;
    PicksTemplateCategory personCategory;
    Pick pick;

    @BeforeEach
    void setUp() {
        ownerId = UUID.randomUUID();
        PickResponseAssembler responseAssembler = new PickResponseAssembler(selectionRepository, categoryRepository,
                templatePreviewAssembler, commentRepository, likeService, pickMapper, targetService,
                new com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor());
        service = new PickSelectionServiceImpl(pickRepository, selectionRepository, categoryRepository,
                templateRepository, targetService, responseAssembler, userBlockAccessService,
                new com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor());
        template = template();
        movieCategory = category(template, PickAllowedType.MOVIE, PickCategoryOptionMode.FIXED, 1);
        personCategory = category(template, PickAllowedType.PERSON, PickCategoryOptionMode.OPEN, 2);
        pick = pick(ownerId, template);
    }

    @Test
    @DisplayName("[upsertSelection] Should Replace Existing Selection - When Category Already Has A Target")
    void shouldReplaceExistingSelectionForTheSameCategory() {
        stubTemplateLock();
        pick.setLikesCount(8);
        Content oldContent = content("550");
        Content newContent = content("551");
        PickSelection existing = selection(pick, movieCategory, oldContent);
        when(pickRepository.findByIdForUpdate(pick.getId())).thenReturn(Optional.of(pick));
        when(categoryRepository.findByIdForUpdate(movieCategory.getId())).thenReturn(Optional.of(movieCategory));
        when(selectionRepository.findByPickIdAndCategoryIdForUpdate(pick.getId(), movieCategory.getId()))
                .thenReturn(Optional.of(existing));
        stubPreparedTarget(movieCategory, new ResolvedPickTarget(newContent, null, null, movieKey("551")));
        when(selectionRepository.save(any())).thenAnswer(invocation -> assignSelectionId(invocation.getArgument(0)));
        stubAssembly(List.of(selection(pick, movieCategory, newContent)), true);
        when(commentRepository.countVisiblePickComments(ownerId, List.of(pick.getId())))
                .thenReturn(List.of(commentCount(pick.getId(), 3)));
        when(likeService.getLikedPickIds(ownerId, List.of(pick.getId()))).thenReturn(Set.of(pick.getId()));
        when(pickMapper.pickSelectionToSearchDto(any())).thenReturn(new PickOptionSearchDTO(newContent.getId(),
                new ContentRefDTO(newContent.getId(), "551", ContentType.MOVIE, null, null, null, null, null, null, null),
                null, null));

        PickResponseDTO result = service.upsertSelection(ownerId, pick.getId(), movieCategory.getId(), movieTarget("551"));

        assertThat(result.likesCount()).isEqualTo(8);
        assertThat(result.commentsCount()).isEqualTo(3);
        assertThat(result.isLikedByViewer()).isTrue();
        assertThat(result.picksTemplate()).isEqualTo(templatePreview(template));
        assertThat(result.selections()).extracting(selection -> selection.target().content().tmdbId())
                .containsExactly("551");
        assertThat(result.selections()).hasSize(1);
        verify(commentRepository).countVisiblePickComments(ownerId, List.of(pick.getId()));
        verify(likeService).getLikedPickIds(ownerId, List.of(pick.getId()));
        verify(templatePreviewAssembler).assemble(anyCollection(), eq(ownerId));
        verify(selectionRepository).delete(existing);
        verify(selectionRepository).flush();
        ArgumentCaptor<PickSelection> captor = ArgumentCaptor.forClass(PickSelection.class);
        verify(selectionRepository).save(captor.capture());
        assertThat(captor.getValue().getContent()).isSameAs(newContent);
    }

    @Test
    @DisplayName("[upsertSelection] Should Allow Replacement In Fixed Category - When Structure Is Already Used")
    void shouldAllowReplacingSelectionInAFixedCategoryBecauseFreezeOnlyProtectsTemplateStructure() {
        stubTemplateLock();
        when(pickRepository.findByIdForUpdate(pick.getId())).thenReturn(Optional.of(pick));
        when(categoryRepository.findByIdForUpdate(movieCategory.getId())).thenReturn(Optional.of(movieCategory));
        when(selectionRepository.findByPickIdAndCategoryIdForUpdate(pick.getId(), movieCategory.getId()))
                .thenReturn(Optional.empty());
        Content content = content("550");
        stubPreparedTarget(movieCategory, new ResolvedPickTarget(content, null, null, movieKey("550")));
        when(selectionRepository.save(any())).thenAnswer(invocation -> assignSelectionId(invocation.getArgument(0)));
        stubAssembly(List.of(selection(pick, movieCategory, content)), true);

        service.upsertSelection(ownerId, pick.getId(), movieCategory.getId(), movieTarget("550"));

        verify(targetService).prepareForCategory(eq(ownerId), any(PicksTemplate.class), argThat(value -> value.getId().equals(movieCategory.getId())), any());
        verify(selectionRepository).save(any(PickSelection.class));
    }

    @Test
    @DisplayName("[upsertSelection] Should Persist Person Context - When Open Category Receives A Person")
    void shouldPersistOpenPersonTargetWithContext() {
        stubTemplateLock();
        Content context = content("550");
        when(pickRepository.findByIdForUpdate(pick.getId())).thenReturn(Optional.of(pick));
        when(categoryRepository.findByIdForUpdate(personCategory.getId())).thenReturn(Optional.of(personCategory));
        when(selectionRepository.findByPickIdAndCategoryIdForUpdate(pick.getId(), personCategory.getId()))
                .thenReturn(Optional.empty());
        stubPreparedTarget(personCategory, new ResolvedPickTarget(null, "42", context,
                        new ResolvedPickTarget.TargetKey(null, "42", movieKey("550").content())));
        when(selectionRepository.save(any())).thenAnswer(invocation -> assignSelectionId(invocation.getArgument(0)));
        stubAssembly(List.of(personSelection(pick, personCategory, "42", context)), true);
        when(pickMapper.pickSelectionToSearchDto(any())).thenReturn(new PickOptionSearchDTO(context.getId(), null,
                "42", new ContentRefDTO(context.getId(), "550", ContentType.MOVIE,
                null, null, null, null, null, null, null)));

        PickResponseDTO response = service.upsertSelection(ownerId, pick.getId(), personCategory.getId(),
                new PickTargetDTO(null, "42", new PickContentTargetDTO(PickAllowedType.MOVIE, "550", null, null, null)));

        assertThat(response.progress()).isEqualTo(PickProgress.PARTIAL);
        assertThat(response.selections()).singleElement().satisfies(selection -> {
            assertThat(selection.categoryId()).isEqualTo(personCategory.getId());
            assertThat(selection.target().personTmdbId()).isEqualTo("42");
            assertThat(selection.target().contextContent().tmdbId()).isEqualTo("550");
        });
        ArgumentCaptor<PickSelection> captor = ArgumentCaptor.forClass(PickSelection.class);
        verify(selectionRepository).save(captor.capture());
        assertThat(captor.getValue().getPersonTmdbId()).isEqualTo("42");
        assertThat(captor.getValue().getContextContent()).isSameAs(context);
    }

    @Test
    @DisplayName("[deleteSelection] Should Delete Selection - When Another Selection Remains")
    void shouldDeleteOneSelectionWhenSeveralRemain() {
        PickSelection existing = selection(pick, movieCategory, content("550"));
        when(pickRepository.findByIdForUpdate(pick.getId())).thenReturn(Optional.of(pick));
        when(selectionRepository.countByPickId(pick.getId())).thenReturn(2L);
        when(selectionRepository.findByPickIdAndCategoryIdForUpdate(pick.getId(), movieCategory.getId()))
                .thenReturn(Optional.of(existing));

        service.deleteSelection(ownerId, pick.getId(), movieCategory.getId());

        verify(selectionRepository).delete(existing);
    }

    @Test
    @DisplayName("[deleteSelection] Should Reject Deletion - When It Is The Last Selection")
    void shouldRejectDeletingTheLastSelection() {
        when(pickRepository.findByIdForUpdate(pick.getId())).thenReturn(Optional.of(pick));
        when(selectionRepository.findByPickIdAndCategoryIdForUpdate(pick.getId(), movieCategory.getId()))
                .thenReturn(Optional.of(selection(pick, movieCategory, content("550"))));
        when(selectionRepository.countByPickId(pick.getId())).thenReturn(1L);

        assertThatThrownBy(() -> service.deleteSelection(ownerId, pick.getId(), movieCategory.getId()))
                .isInstanceOf(ConflictException.class);

        verify(selectionRepository, never()).delete(any());
    }

    @Test
    @DisplayName("[deleteSelection] Should Report Missing Selection - Before Checking Last-Selection Rule")
    void shouldReportMissingSelectionBeforeLastSelectionConflict() {
        when(pickRepository.findByIdForUpdate(pick.getId())).thenReturn(Optional.of(pick));
        when(selectionRepository.findByPickIdAndCategoryIdForUpdate(pick.getId(), movieCategory.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteSelection(ownerId, pick.getId(), movieCategory.getId()))
                .isInstanceOf(NotFoundException.class);

        verify(selectionRepository, never()).countByPickId(pick.getId());
        verify(selectionRepository, never()).delete(any());
    }

    @Test
    @DisplayName("[selectionMutations] Should Restrict Mutations - When Pick Belongs To Another User")
    void shouldRestrictSelectionMutationsToThePickOwner() {
        UUID strangerId = UUID.randomUUID();
        when(pickRepository.findOwnedTemplateId(pick.getId(), strangerId)).thenReturn(Optional.empty());
        when(pickRepository.findByIdForUpdate(pick.getId())).thenReturn(Optional.of(pick));

        assertThatThrownBy(() -> service.upsertSelection(strangerId, pick.getId(), movieCategory.getId(), movieTarget("550")))
                .isInstanceOf(NotFoundException.class);
        verify(pickRepository).findOwnedTemplateId(pick.getId(), strangerId);
        verify(templateRepository, never()).findByIdForUpdate(any());

        assertThatThrownBy(() -> service.deleteSelection(strangerId, pick.getId(), movieCategory.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("[upsertSelection] Should Report Invalid Selection - When Template Rules Change")
    void shouldReportInvalidSelectionsAfterTemplateRulesChange() {
        stubTemplateLock();
        PickSelection selection = selection(pick, movieCategory, content("550"));
        when(pickRepository.findByIdForUpdate(pick.getId())).thenReturn(Optional.of(pick));
        when(categoryRepository.findByIdForUpdate(movieCategory.getId())).thenReturn(Optional.of(movieCategory));
        when(selectionRepository.findByPickIdAndCategoryIdForUpdate(pick.getId(), movieCategory.getId()))
                .thenReturn(Optional.of(selection));
        stubPreparedTarget(movieCategory, new ResolvedPickTarget(content("551"), null, null, movieKey("551")));
        when(selectionRepository.save(any())).thenAnswer(invocation -> assignSelectionId(invocation.getArgument(0)));
        stubAssembly(List.of(selection(pick, movieCategory, content("551"))), false);

        PickResponseDTO result = service.upsertSelection(ownerId, pick.getId(), movieCategory.getId(), movieTarget("551"));

        assertThat(result.progress()).isEqualTo(PickProgress.EMPTY);
        assertThat(result.selections()).extracting(PickSelectionDTO::isValid).containsExactly(false);
    }

    private void stubTemplateLock() {
        when(pickRepository.findOwnedTemplateId(pick.getId(), ownerId)).thenReturn(Optional.of(template.getId()));
        when(templateRepository.findCreatorIdentityById(template.getId()))
                .thenReturn(Optional.of(mock(PicksTemplateRepository.CreatorIdentity.class)));
        when(templateRepository.findByIdForUpdate(template.getId())).thenReturn(Optional.of(template));
        when(templateRepository.findById(template.getId())).thenReturn(Optional.of(template));
        when(categoryRepository.findById(any())).thenAnswer(call -> Optional.of(
                movieCategory.getId().equals(call.getArgument(0)) ? movieCategory : personCategory));
    }

    private void stubAssembly(List<PickSelection> selections, boolean valid) {
        when(selectionRepository.findByPickIdIn(List.of(pick.getId()))).thenReturn(selections);
        when(categoryRepository.findByPicksTemplateIdOrderByGroupAscDisplayOrderAsc(template.getId()))
                .thenReturn(List.of(movieCategory, personCategory));
        when(targetService.isValidPrepared(same(template), any(), any(), any())).thenReturn(valid);
        when(pickMapper.pickSelectionToSearchDto(any())).thenReturn(new PickOptionSearchDTO(UUID.randomUUID(), null, null, null));
        when(templatePreviewAssembler.assemble(anyCollection(), eq(ownerId)))
                .thenReturn(Map.of(template.getId(), templatePreview(template)));
    }

    private void stubPreparedTarget(PicksTemplateCategory category, ResolvedPickTarget resolved) {
        PreparedPickTarget prepared = new PreparedPickTarget(resolved.key(), null, null,
                resolved.content() == null ? null : resolved.content().getId(),
                resolved.contextContent() == null ? null : resolved.contextContent().getId(),
                new PreparedTargetValidation(resolved.key(), true, null));
        when(targetService.prepareForCategory(eq(ownerId), any(PicksTemplate.class), argThat(value -> value.getId().equals(category.getId())), any())).thenReturn(prepared);
        when(targetService.resolvePreparedForCategory(same(template), same(category), same(prepared))).thenReturn(resolved);
    }

    private CommentRepository.PickCommentCount commentCount(UUID pickId, long count) {
        return new CommentRepository.PickCommentCount() {
            @Override public UUID getPickId() { return pickId; }
            @Override public long getCount() { return count; }
        };
    }

    private PickSelection assignSelectionId(PickSelection selection) {
        selection.setId(UUID.randomUUID());
        return selection;
    }

    private PicksTemplate template() {
        return PicksTemplate.builder().id(UUID.randomUUID()).origin(PickOrigin.COMMUNITY).name("Awards")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    private PicksTemplateCategory category(PicksTemplate template, PickAllowedType type, PickCategoryOptionMode mode, int order) {
        return PicksTemplateCategory.builder().id(UUID.randomUUID()).picksTemplate(template).name("Category")
                .group(PickCategoryGroup.PRIMARY).displayOrder(order).allowedType(type).optionMode(mode)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    private Pick pick(UUID ownerId, PicksTemplate template) {
        return Pick.builder().id(UUID.randomUUID()).picksTemplate(template).user(user(ownerId)).visibility(PickVisibility.PUBLIC)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    private PickSelection selection(Pick pick, PicksTemplateCategory category, Content content) {
        return PickSelection.builder().id(UUID.randomUUID()).pick(pick).category(category).content(content)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    private PickSelection personSelection(Pick pick, PicksTemplateCategory category, String personTmdbId, Content context) {
        return PickSelection.builder().id(UUID.randomUUID()).pick(pick).category(category).personTmdbId(personTmdbId)
                .contextContent(context).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    private User user(UUID id) {
        return User.builder().id(id).username("user").email("user@example.com").password("hash")
                .isProfilePublic(true).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    private PicksTemplatePreviewDTO templatePreview(PicksTemplate template) {
        return new PicksTemplatePreviewDTO(template.getId(), template.getOrigin(), template.getName(),
                template.getDescription(), template.getCoverImage());
    }

    private Content content(String tmdbId) {
        return Content.builder().id(UUID.randomUUID()).type(ContentType.MOVIE).tmdbId(tmdbId).build();
    }

    private PickTargetDTO movieTarget(String tmdbId) {
        return new PickTargetDTO(new PickContentTargetDTO(PickAllowedType.MOVIE, tmdbId, null, null, null), null, null);
    }

    private ResolvedPickTarget.TargetKey movieKey(String tmdbId) {
        return new ResolvedPickTarget.TargetKey(new ResolvedPickTarget.ContentKey(ContentType.MOVIE, tmdbId, null, null, null), null, null);
    }
}
