package com.watchwise.watchwise_api.pick.service.impl;

import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
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
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplatePreviewDTO;
import com.watchwise.watchwise_api.pickstemplate.entity.*;
import com.watchwise.watchwise_api.pickstemplate.mapper.PicksTemplateMapper;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateCategoryRepository;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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
    @Mock PicksTemplateMapper templateMapper;

    PickSelectionService service;
    UUID ownerId;
    PicksTemplate template;
    PicksTemplateCategory movieCategory;
    PicksTemplateCategory personCategory;
    Pick pick;

    @BeforeEach
    void setUp() {
        service = new PickSelectionServiceImpl(pickRepository, selectionRepository, categoryRepository,
                targetService, pickMapper, templateMapper);
        ownerId = UUID.randomUUID();
        template = template();
        movieCategory = category(template, PickAllowedType.MOVIE, PickCategoryOptionMode.FIXED, 1);
        personCategory = category(template, PickAllowedType.PERSON, PickCategoryOptionMode.OPEN, 2);
        pick = pick(ownerId, template);
    }

    @Test
    void shouldReplaceExistingSelectionForTheSameCategory() {
        Content oldContent = content("550");
        Content newContent = content("551");
        PickSelection existing = selection(pick, movieCategory, oldContent);
        when(pickRepository.findByIdForUpdate(pick.getId())).thenReturn(Optional.of(pick));
        when(categoryRepository.findByIdForUpdate(movieCategory.getId())).thenReturn(Optional.of(movieCategory));
        when(selectionRepository.findByPickIdAndCategoryIdForUpdate(pick.getId(), movieCategory.getId()))
                .thenReturn(Optional.of(existing));
        when(targetService.validateForCategory(eq(ownerId), same(template), same(movieCategory), any()))
                .thenReturn(new ResolvedPickTarget(newContent, null, null, movieKey("551")));
        when(selectionRepository.save(any())).thenAnswer(invocation -> assignSelectionId(invocation.getArgument(0)));
        stubAssembly(List.of(selection(pick, movieCategory, newContent)), true);

        PickResponseDTO result = service.upsertSelection(ownerId, pick.getId(), movieCategory.getId(), movieTarget("551"));

        assertThat(result.selections()).hasSize(1);
        verify(selectionRepository).delete(existing);
        verify(selectionRepository).flush();
        ArgumentCaptor<PickSelection> captor = ArgumentCaptor.forClass(PickSelection.class);
        verify(selectionRepository).save(captor.capture());
        assertThat(captor.getValue().getContent()).isSameAs(newContent);
    }

    @Test
    void shouldAllowReplacingSelectionInAFixedCategoryBecauseFreezeOnlyProtectsTemplateStructure() {
        when(pickRepository.findByIdForUpdate(pick.getId())).thenReturn(Optional.of(pick));
        when(categoryRepository.findByIdForUpdate(movieCategory.getId())).thenReturn(Optional.of(movieCategory));
        when(selectionRepository.findByPickIdAndCategoryIdForUpdate(pick.getId(), movieCategory.getId()))
                .thenReturn(Optional.empty());
        Content content = content("550");
        when(targetService.validateForCategory(eq(ownerId), same(template), same(movieCategory), any()))
                .thenReturn(new ResolvedPickTarget(content, null, null, movieKey("550")));
        when(selectionRepository.save(any())).thenAnswer(invocation -> assignSelectionId(invocation.getArgument(0)));
        stubAssembly(List.of(selection(pick, movieCategory, content)), true);

        service.upsertSelection(ownerId, pick.getId(), movieCategory.getId(), movieTarget("550"));

        verify(targetService).validateForCategory(eq(ownerId), same(template), same(movieCategory), any());
        verify(selectionRepository).save(any(PickSelection.class));
    }

    @Test
    void shouldPersistOpenPersonTargetWithContext() {
        Content context = content("550");
        when(pickRepository.findByIdForUpdate(pick.getId())).thenReturn(Optional.of(pick));
        when(categoryRepository.findByIdForUpdate(personCategory.getId())).thenReturn(Optional.of(personCategory));
        when(selectionRepository.findByPickIdAndCategoryIdForUpdate(pick.getId(), personCategory.getId()))
                .thenReturn(Optional.empty());
        when(targetService.validateForCategory(eq(ownerId), same(template), same(personCategory), any()))
                .thenReturn(new ResolvedPickTarget(null, "42", context,
                        new ResolvedPickTarget.TargetKey(null, "42", movieKey("550").content())));
        when(selectionRepository.save(any())).thenAnswer(invocation -> assignSelectionId(invocation.getArgument(0)));
        stubAssembly(List.of(personSelection(pick, personCategory, "42", context)), true);

        service.upsertSelection(ownerId, pick.getId(), personCategory.getId(),
                new PickTargetDTO(null, "42", new PickContentTargetDTO(PickAllowedType.MOVIE, "550", null, null, null)));

        ArgumentCaptor<PickSelection> captor = ArgumentCaptor.forClass(PickSelection.class);
        verify(selectionRepository).save(captor.capture());
        assertThat(captor.getValue().getPersonTmdbId()).isEqualTo("42");
        assertThat(captor.getValue().getContextContent()).isSameAs(context);
    }

    @Test
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
    void shouldRejectDeletingTheLastSelection() {
        when(pickRepository.findByIdForUpdate(pick.getId())).thenReturn(Optional.of(pick));
        when(selectionRepository.countByPickId(pick.getId())).thenReturn(1L);

        assertThatThrownBy(() -> service.deleteSelection(ownerId, pick.getId(), movieCategory.getId()))
                .isInstanceOf(ConflictException.class);

        verify(selectionRepository, never()).delete(any());
    }

    @Test
    void shouldRestrictSelectionMutationsToThePickOwner() {
        when(pickRepository.findByIdForUpdate(pick.getId())).thenReturn(Optional.of(pick));

        assertThatThrownBy(() -> service.upsertSelection(UUID.randomUUID(), pick.getId(), movieCategory.getId(), movieTarget("550")))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.deleteSelection(UUID.randomUUID(), pick.getId(), movieCategory.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void shouldReportInvalidSelectionsAfterTemplateRulesChange() {
        PickSelection selection = selection(pick, movieCategory, content("550"));
        when(pickRepository.findByIdForUpdate(pick.getId())).thenReturn(Optional.of(pick));
        when(categoryRepository.findByIdForUpdate(movieCategory.getId())).thenReturn(Optional.of(movieCategory));
        when(selectionRepository.findByPickIdAndCategoryIdForUpdate(pick.getId(), movieCategory.getId()))
                .thenReturn(Optional.of(selection));
        when(targetService.validateForCategory(eq(ownerId), same(template), same(movieCategory), any()))
                .thenReturn(new ResolvedPickTarget(content("551"), null, null, movieKey("551")));
        when(selectionRepository.save(any())).thenAnswer(invocation -> assignSelectionId(invocation.getArgument(0)));
        stubAssembly(List.of(selection(pick, movieCategory, content("551"))), false);

        PickResponseDTO result = service.upsertSelection(ownerId, pick.getId(), movieCategory.getId(), movieTarget("551"));

        assertThat(result.progress()).isEqualTo(PickProgress.EMPTY);
        assertThat(result.selections()).extracting(PickSelectionDTO::isValid).containsExactly(false);
    }

    private void stubAssembly(List<PickSelection> selections, boolean valid) {
        when(selectionRepository.findByPickIdIn(List.of(pick.getId()))).thenReturn(selections);
        when(categoryRepository.findByPicksTemplateIdOrderByGroupAscDisplayOrderAsc(template.getId()))
                .thenReturn(List.of(movieCategory, personCategory));
        when(targetService.isValid(eq(ownerId), same(template), any(), any())).thenReturn(valid);
        when(pickMapper.pickSelectionToSearchDto(any())).thenReturn(new PickOptionSearchDTO(UUID.randomUUID(), null, null, null));
        when(templateMapper.picksTemplateToPreviewDto(template)).thenReturn(
                new PicksTemplatePreviewDTO(template.getId(), template.getOrigin(), template.getName(), null, null));
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
