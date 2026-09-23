package com.watchwise.watchwise_api.pick.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.pick.dto.*;
import com.watchwise.watchwise_api.pick.entity.Pick;
import com.watchwise.watchwise_api.pick.entity.PickSelection;
import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pick.mapper.PickMapper;
import com.watchwise.watchwise_api.pick.repository.PickRepository;
import com.watchwise.watchwise_api.pick.repository.PickSelectionRepository;
import com.watchwise.watchwise_api.pick.service.PickService;
import com.watchwise.watchwise_api.pick.service.PickTargetService;
import com.watchwise.watchwise_api.pick.service.ResolvedPickTarget;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplatePreviewDTO;
import com.watchwise.watchwise_api.pickstemplate.entity.*;
import com.watchwise.watchwise_api.pickstemplate.mapper.PicksTemplateMapper;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateCategoryRepository;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateRepository;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateOptionRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.like.service.LikeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PickServiceImplTest {
    @Mock PickRepository pickRepository;
    @Mock PickSelectionRepository selectionRepository;
    @Mock PicksTemplateRepository templateRepository;
    @Mock PicksTemplateCategoryRepository categoryRepository;
    @Mock UserRepository userRepository;
    @Mock FollowerRepository followerRepository;
    @Mock PickTargetService targetService;
    @Mock PickMapper pickMapper;
    @Mock PicksTemplateMapper templateMapper;
    @Mock CommentRepository commentRepository;
    @Mock LikeService likeService;
    @Mock UserMapper userMapper;
    @Mock PicksTemplateOptionRepository optionRepository;

    PickService service;
    PageRequestFactory pageRequestFactory;
    List<PickSelection> savedSelections;

    @BeforeEach
    void setUp() {
        pageRequestFactory = new PageRequestFactory();
        PickPreviewAssembler pickPreviewAssembler = new PickPreviewAssembler(selectionRepository, categoryRepository,
                optionRepository, commentRepository, likeService, pickMapper, targetService, userMapper);
        service = new PickServiceImpl(pickRepository, selectionRepository, templateRepository, categoryRepository,
                userRepository, followerRepository, targetService, pickMapper, templateMapper, pageRequestFactory,
                pickPreviewAssembler);
        savedSelections = new ArrayList<>();
    }

    @Test
    void shouldCreateOneSelectionPickWithoutRequiringAUniqueUserTemplatePair() {
        UUID userId = UUID.randomUUID();
        PicksTemplate template = template();
        PicksTemplateCategory movie = category(template, PickAllowedType.MOVIE, 1);
        Content content = content("550");
        PickCreationDTO request = new PickCreationDTO(PickVisibility.FOLLOWERS, List.of(selectionRequest(movie.getId(), "550")));
        stubCreation(userId, template, List.of(movie));
        when(targetService.validateForCategory(eq(userId), same(template), same(movie), any()))
                .thenReturn(new ResolvedPickTarget(content, null, null, movieKey("550")));
        when(pickRepository.save(any())).thenAnswer(invocation -> assignPickId(invocation.getArgument(0)));
        stubSavedSelectionLookup();
        when(pickMapper.pickSelectionToSearchDto(any())).thenReturn(new PickOptionSearchDTO(UUID.randomUUID(), null, null, null));
        when(targetService.isValid(eq(userId), same(template), same(movie), any())).thenReturn(true);
        lenient().when(templateMapper.picksTemplateToPreviewDto(template)).thenReturn(templatePreview(template));

        PickResponseDTO result = service.createPick(userId, template.getId(), request);

        assertThat(result.visibility()).isEqualTo(PickVisibility.FOLLOWERS);
        assertThat(result.progress()).isEqualTo(PickProgress.COMPLETE);
        assertThat(result.selections()).hasSize(1);
        verify(pickRepository, never()).existsByPicksTemplateId(any());
        verify(pickRepository).save(any(Pick.class));
        verify(selectionRepository).save(any(PickSelection.class));
    }

    @Test
    void shouldPersistPartialPickWhenOnlySomeTemplateCategoriesHaveSelections() {
        UUID userId = UUID.randomUUID();
        PicksTemplate template = template();
        PicksTemplateCategory movie = category(template, PickAllowedType.MOVIE, 1);
        PicksTemplateCategory series = category(template, PickAllowedType.SERIES, 2);
        Content content = content("550");
        stubCreation(userId, template, List.of(movie, series));
        when(targetService.validateForCategory(eq(userId), same(template), same(movie), any()))
                .thenReturn(new ResolvedPickTarget(content, null, null, movieKey("550")));
        when(pickRepository.save(any())).thenAnswer(invocation -> assignPickId(invocation.getArgument(0)));
        stubSavedSelectionLookup();
        when(pickMapper.pickSelectionToSearchDto(any())).thenReturn(new PickOptionSearchDTO(UUID.randomUUID(), null, null, null));
        when(targetService.isValid(eq(userId), same(template), same(movie), any())).thenReturn(true);
        lenient().when(templateMapper.picksTemplateToPreviewDto(template)).thenReturn(templatePreview(template));

        PickResponseDTO result = service.createPick(userId, template.getId(),
                new PickCreationDTO(PickVisibility.PUBLIC, List.of(selectionRequest(movie.getId(), "550"))));

        assertThat(result.progress()).isEqualTo(PickProgress.PARTIAL);
    }

    @Test
    void shouldRejectZeroSelectionsBeforeAnyWrite() {
        assertThatThrownBy(() -> service.createPick(UUID.randomUUID(), UUID.randomUUID(),
                new PickCreationDTO(PickVisibility.PUBLIC, List.of()))).isInstanceOf(BadRequestException.class);

        verifyNoInteractions(templateRepository, pickRepository, selectionRepository);
    }

    @Test
    void shouldRejectDuplicateCategoryIdsBeforeAnyWrite() {
        UUID categoryId = UUID.randomUUID();

        assertThatThrownBy(() -> service.createPick(UUID.randomUUID(), UUID.randomUUID(),
                new PickCreationDTO(PickVisibility.PUBLIC, List.of(selectionRequest(categoryId, "550"), selectionRequest(categoryId, "551")))))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(templateRepository, pickRepository, selectionRepository);
    }

    @Test
    void shouldRollbackCreationWhenSelectionValidationFailsBeforeThePickIsSaved() {
        UUID userId = UUID.randomUUID();
        PicksTemplate template = template();
        PicksTemplateCategory category = category(template, PickAllowedType.MOVIE, 1);
        stubCreationWithoutUserReference(template, List.of(category));
        when(targetService.validateForCategory(eq(userId), same(template), same(category), any()))
                .thenThrow(new BadRequestException("Target is not eligible"));

        assertThatThrownBy(() -> service.createPick(userId, template.getId(),
                new PickCreationDTO(PickVisibility.PUBLIC, List.of(selectionRequest(category.getId(), "550")))))
                .isInstanceOf(BadRequestException.class);

        verify(pickRepository, never()).save(any());
        verify(selectionRepository, never()).save(any());
    }

    @Test
    void shouldCreateIndependentPickIdsForRepeatedUserTemplateSubmissions() {
        UUID userId = UUID.randomUUID();
        PicksTemplate template = template();
        PicksTemplateCategory category = category(template, PickAllowedType.MOVIE, 1);
        stubCreation(userId, template, List.of(category));
        when(targetService.validateForCategory(eq(userId), same(template), same(category), any()))
                .thenReturn(new ResolvedPickTarget(content("550"), null, null, movieKey("550")));
        when(pickRepository.save(any())).thenAnswer(invocation -> assignPickId(invocation.getArgument(0)));
        stubSavedSelectionLookup();
        when(pickMapper.pickSelectionToSearchDto(any())).thenReturn(new PickOptionSearchDTO(UUID.randomUUID(), null, null, null));
        when(targetService.isValid(eq(userId), same(template), same(category), any())).thenReturn(true);
        when(templateMapper.picksTemplateToPreviewDto(template)).thenReturn(templatePreview(template));

        PickCreationDTO request = new PickCreationDTO(PickVisibility.PUBLIC, List.of(selectionRequest(category.getId(), "550")));
        PickResponseDTO first = service.createPick(userId, template.getId(), request);
        PickResponseDTO second = service.createPick(userId, template.getId(), request);

        assertThat(first.id()).isNotEqualTo(second.id());
        verify(pickRepository, times(2)).save(any(Pick.class));
    }

    @Test
    void shouldBatchLoadSelectionsForOwnPaginatedPicks() {
        UUID userId = UUID.randomUUID();
        PicksTemplate template = template();
        PicksTemplateCategory category = category(template, PickAllowedType.MOVIE, 1);
        Pick first = pick(userId, template, PickVisibility.PUBLIC);
        Pick second = pick(userId, template, PickVisibility.PRIVATE);
        PickSelection firstSelection = persistedSelection(first, category, content("550"));
        PickSelection secondSelection = persistedSelection(second, category, content("551"));
        PageRequest pageRequest = PageRequest.of(0, 10);
        when(templateRepository.existsById(template.getId())).thenReturn(true);
        when(pickRepository.findByUserIdAndPicksTemplateIdOrderByCreatedAtDescIdDesc(userId, template.getId(), pageRequest))
                .thenReturn(new PageImpl<>(List.of(first, second), pageRequest, 2));
        when(selectionRepository.findByPickIdIn(List.of(first.getId(), second.getId())))
                .thenReturn(List.of(firstSelection, secondSelection));
        when(categoryRepository.findByPicksTemplateIdOrderByGroupAscDisplayOrderAsc(template.getId())).thenReturn(List.of(category));
        lenient().when(targetService.isStructurallyValid(same(category), any(), any())).thenReturn(true);
        when(pickMapper.pickSelectionToSearchDto(any())).thenReturn(new PickOptionSearchDTO(UUID.randomUUID(), null, null, null));
        lenient().when(templateMapper.picksTemplateToPreviewDto(template)).thenReturn(templatePreview(template));

        var result = service.getMyPicks(userId, template.getId(), 1, 10);

        assertThat(result.getContent()).hasSize(2);
        verify(selectionRepository).findByPickIdIn(List.of(first.getId(), second.getId()));
    }

    @Test
    void shouldDefaultTemplatePicksToRecentAndBatchMapPreviewData() {
        UUID viewerId = UUID.randomUUID();
        PicksTemplate template = template();
        PicksTemplateCategory category = category(template, PickAllowedType.MOVIE, 1);
        Pick pick = pick(UUID.randomUUID(), template, PickVisibility.PUBLIC);
        PickSelection selection = persistedSelection(pick, category, content("550"));
        PageRequest pageRequest = PageRequest.of(0, 10);
        when(templateRepository.existsById(template.getId())).thenReturn(true);
        when(pickRepository.findVisibleByTemplateRecent(viewerId, template.getId(), pageRequest))
                .thenReturn(new PageImpl<>(List.of(pick), pageRequest, 1));
        when(selectionRepository.findByPickIdIn(List.of(pick.getId()))).thenReturn(List.of(selection));
        when(categoryRepository.findByPicksTemplateIdOrderByGroupAscDisplayOrderAsc(template.getId()))
                .thenReturn(List.of(category));
        when(targetService.isStructurallyValid(same(category), same(selection), anyList())).thenReturn(true);
        when(pickMapper.pickSelectionToSearchDto(selection))
                .thenReturn(new PickOptionSearchDTO(UUID.randomUUID(), null, null, null));

        Page<PickPreviewDTO> result = service.getTemplatePicks(viewerId, template.getId(), null, 1, 10);

        assertThat(result.getContent()).extracting(PickPreviewDTO::id).containsExactly(pick.getId());
        assertThat(result.getContent().getFirst().answeredCategories()).hasSize(1);
        verify(pickRepository).findVisibleByTemplateRecent(viewerId, template.getId(), pageRequest);
        verify(pickRepository, never()).findVisibleByTemplatePopular(any(), any(), any());
        verify(selectionRepository).findByPickIdIn(List.of(pick.getId()));
    }

    @Test
    void shouldDispatchPopularTemplatePicksToPopularRepositoryQuery() {
        UUID viewerId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        PageRequest pageRequest = PageRequest.of(0, 10);
        when(templateRepository.existsById(templateId)).thenReturn(true);
        when(pickRepository.findVisibleByTemplatePopular(viewerId, templateId, pageRequest))
                .thenReturn(new PageImpl<>(List.of(), pageRequest, 0));

        assertThat(service.getTemplatePicks(viewerId, templateId, PickSort.POPULAR, 1, 10)).isEmpty();

        verify(pickRepository).findVisibleByTemplatePopular(viewerId, templateId, pageRequest);
        verify(pickRepository, never()).findVisibleByTemplateRecent(any(), any(), any());
    }

    @Test
    void shouldRejectTemplatePicksWhenTemplateDoesNotExist() {
        UUID templateId = UUID.randomUUID();
        when(templateRepository.existsById(templateId)).thenReturn(false);

        assertThatThrownBy(() -> service.getTemplatePicks(UUID.randomUUID(), templateId, null, 1, 10))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(pickRepository, selectionRepository, categoryRepository);
    }

    @Test
    void shouldDelegateThirdPartyVisibilityFilteringToRepository() {
        UUID viewerId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        PageRequest pageRequest = PageRequest.of(0, 10);
        when(userRepository.existsById(ownerId)).thenReturn(true);
        when(pickRepository.findVisibleByOwner(viewerId, ownerId, templateId, pageRequest))
                .thenReturn(new PageImpl<>(List.of(), pageRequest, 0));

        service.getUserPicks(viewerId, ownerId, templateId, 1, 10);

        verify(pickRepository).findVisibleByOwner(viewerId, ownerId, templateId, pageRequest);
        verify(selectionRepository, never()).findByPickIdIn(any());
    }

    @Test
    void shouldAllowFollowersToReadFollowerVisiblePickButRejectPrivatePick() {
        UUID viewerId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        PicksTemplate template = template();
        PicksTemplateCategory category = category(template, PickAllowedType.MOVIE, 1);
        Pick followersPick = pick(ownerId, template, PickVisibility.FOLLOWERS);
        Pick privatePick = pick(ownerId, template, PickVisibility.PRIVATE);
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(viewerId, ownerId, FollowStatus.ACCEPTED))
                .thenReturn(true);
        when(pickRepository.findById(followersPick.getId())).thenReturn(Optional.of(followersPick));
        when(selectionRepository.findByPickIdIn(List.of(followersPick.getId())))
                .thenReturn(List.of(persistedSelection(followersPick, category, content("550"))));
        when(categoryRepository.findByPicksTemplateIdOrderByGroupAscDisplayOrderAsc(template.getId())).thenReturn(List.of(category));
        when(targetService.isValid(eq(viewerId), same(template), same(category), any())).thenReturn(true);
        when(pickMapper.pickSelectionToSearchDto(any())).thenReturn(new PickOptionSearchDTO(UUID.randomUUID(), null, null, null));
        when(templateMapper.picksTemplateToPreviewDto(template)).thenReturn(templatePreview(template));

        assertThat(service.getPick(viewerId, followersPick.getId()).id()).isEqualTo(followersPick.getId());

        when(pickRepository.findById(privatePick.getId())).thenReturn(Optional.of(privatePick));
        assertThatThrownBy(() -> service.getPick(viewerId, privatePick.getId())).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void shouldRestrictPatchAndDeleteToTheOwner() {
        UUID ownerId = UUID.randomUUID();
        Pick pick = pick(ownerId, template(), PickVisibility.PUBLIC);
        when(pickRepository.findByIdForUpdate(pick.getId())).thenReturn(Optional.of(pick));

        assertThatThrownBy(() -> service.updatePick(UUID.randomUUID(), pick.getId(), new PickPatchDTO(PickVisibility.PRIVATE)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.deletePick(UUID.randomUUID(), pick.getId())).isInstanceOf(NotFoundException.class);
    }

    private void stubCreation(UUID userId, PicksTemplate template, List<PicksTemplateCategory> categories) {
        stubCreationWithoutUserReference(template, categories);
        when(userRepository.getReferenceById(userId)).thenReturn(user(userId));
    }

    private void stubCreationWithoutUserReference(PicksTemplate template, List<PicksTemplateCategory> categories) {
        when(templateRepository.findByIdForUpdate(template.getId())).thenReturn(Optional.of(template));
        when(categoryRepository.findByPicksTemplateIdOrderByGroupAscDisplayOrderAsc(template.getId())).thenReturn(categories);
    }

    private Pick assignPickId(Pick pick) {
        pick.setId(UUID.randomUUID());
        return pick;
    }

    private PickSelection assignSelectionId(PickSelection selection) {
        selection.setId(UUID.randomUUID());
        return selection;
    }

    private void stubSavedSelectionLookup() {
        when(selectionRepository.save(any())).thenAnswer(invocation -> {
            PickSelection selection = assignSelectionId(invocation.getArgument(0));
            savedSelections.add(selection);
            return selection;
        });
        when(selectionRepository.findByPickIdIn(anyCollection())).thenAnswer(invocation -> {
            List<UUID> pickIds = invocation.getArgument(0);
            return savedSelections.stream()
                    .filter(selection -> pickIds.contains(selection.getPick().getId()))
                    .toList();
        });
    }

    private PicksTemplate template() {
        return PicksTemplate.builder().id(UUID.randomUUID()).origin(PickOrigin.COMMUNITY).name("Awards")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    private PicksTemplateCategory category(PicksTemplate template, PickAllowedType type, int order) {
        return PicksTemplateCategory.builder().id(UUID.randomUUID()).picksTemplate(template).name("Best")
                .group(PickCategoryGroup.PRIMARY).displayOrder(order).allowedType(type).optionMode(PickCategoryOptionMode.OPEN)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    private Pick pick(UUID ownerId, PicksTemplate template, PickVisibility visibility) {
        return Pick.builder().id(UUID.randomUUID()).user(user(ownerId)).picksTemplate(template).visibility(visibility)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    private PickSelection persistedSelection(Pick pick, PicksTemplateCategory category, Content content) {
        return PickSelection.builder().id(UUID.randomUUID()).pick(pick).category(category).content(content)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    private PickSelectionCreationDTO selectionRequest(UUID categoryId, String tmdbId) {
        return new PickSelectionCreationDTO(categoryId,
                new PickTargetDTO(new PickContentTargetDTO(PickAllowedType.MOVIE, tmdbId, null, null, null), null, null));
    }

    private Content content(String tmdbId) {
        return Content.builder().id(UUID.randomUUID()).type(ContentType.MOVIE).tmdbId(tmdbId).build();
    }

    private User user(UUID id) {
        return User.builder().id(id).username("user").email("user@example.com").password("hash")
                .isProfilePublic(true).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    private PicksTemplatePreviewDTO templatePreview(PicksTemplate template) {
        return new PicksTemplatePreviewDTO(template.getId(), template.getOrigin(), template.getName(), template.getDescription(), template.getCoverImage());
    }

    private ResolvedPickTarget.TargetKey movieKey(String tmdbId) {
        return new ResolvedPickTarget.TargetKey(new ResolvedPickTarget.ContentKey(ContentType.MOVIE, tmdbId, null, null, null), null, null);
    }
}
