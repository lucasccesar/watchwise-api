package com.watchwise.watchwise_api.pickstemplate.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.pick.repository.PickRepository;
import com.watchwise.watchwise_api.pick.repository.PickSelectionRepository;
import com.watchwise.watchwise_api.pick.service.PickTargetService;
import com.watchwise.watchwise_api.pickstemplate.dto.*;
import com.watchwise.watchwise_api.pickstemplate.entity.*;
import com.watchwise.watchwise_api.pickstemplate.service.PreparedTemplateCreation;
import com.watchwise.watchwise_api.pickstemplate.service.PreparedTemplateCreationService;
import com.watchwise.watchwise_api.platformimport.service.ImportLedger;
import com.watchwise.watchwise_api.pickstemplate.mapper.PicksTemplateMapper;
import com.watchwise.watchwise_api.pickstemplate.mapper.PicksTemplateOptionMapper;
import com.watchwise.watchwise_api.pickstemplate.repository.*;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.entity.UserRole;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userblock.service.UserBlockAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PicksTemplateServiceImplTest {
    @Mock PicksTemplateRepository templateRepository;
    @Mock PicksTemplateCategoryRepository categoryRepository;
    @Mock PicksTemplateOptionRepository optionRepository;
    @Mock PickRepository pickRepository;
    @Mock PickSelectionRepository selectionRepository;
    @Mock UserRepository userRepository;
    @Mock PreparedTemplateCreationService preparedTemplateCreationService;
    @Mock ImportLedger importLedger;
    @Mock PicksTemplateResponseAssembler responseAssembler;
    @Mock PickTargetService pickTargetService;
    @Mock PicksTemplateMapper templateMapper;
    @Mock PicksTemplateOptionMapper optionMapper;
    @Mock UserMapper userMapper;
    @Mock PageRequestFactory pageRequestFactory;
    @Mock PicksTemplatePreviewAssembler previewAssembler;
    @Mock UserBlockAccessService userBlockAccessService;
    @InjectMocks PicksTemplateServiceImpl service;

    @Test
    @DisplayName("[getMyTemplatesView] Should Query And Assemble The Requested Creator Page - When Sort Is Recent")
    void shouldQueryAndAssembleRequestedCreatorPageWhenSortIsRecent() {
        UUID viewerId = UUID.randomUUID();
        PicksTemplate template = template(null, PickOrigin.COMMUNITY);
        PageRequest request = PageRequest.of(1, 5);
        Page<PicksTemplate> repositoryPage = new PageImpl<>(List.of(template), request, 6);
        Page<PicksTemplatePreviewDTO> assembledPage = new PageImpl<>(List.of(
                new PicksTemplatePreviewDTO(template.getId(), PickOrigin.COMMUNITY, "Awards", null, null)), request, 6);
        when(pageRequestFactory.build(2, 5)).thenReturn(request);
        when(templateRepository.findByCreatorIdOrderByCreatedAtDescIdDesc(viewerId, request)).thenReturn(repositoryPage);
        when(previewAssembler.assemblePage(repositoryPage, viewerId)).thenReturn(assembledPage);

        Page<PicksTemplatePreviewDTO> result = service.getMyTemplatesView(viewerId, 2, 5, PicksTemplateSort.RECENT);

        assertThat(result).isSameAs(assembledPage);
        verify(templateRepository).findByCreatorIdOrderByCreatedAtDescIdDesc(viewerId, request);
        verify(previewAssembler).assemblePage(repositoryPage, viewerId);
        verify(templateRepository, never()).search(any(), any(), any(), any());
    }

    @Test
    void shouldUseAlphabeticalSearchForDefaultSortAndAssembleReturnedPage() {
        UUID viewerId = UUID.randomUUID();
        PageRequest pageRequest = PageRequest.of(0, 20);
        Page<PicksTemplate> repositoryPage = new PageImpl<>(List.of(template(null, PickOrigin.COMMUNITY)), pageRequest, 1);
        Page<PicksTemplatePreviewDTO> assembledPage = new PageImpl<>(List.of(), pageRequest, 1);
        when(pageRequestFactory.build(1, 20)).thenReturn(pageRequest);
        when(templateRepository.search(viewerId, PickOrigin.COMMUNITY, "A\\%", pageRequest)).thenReturn(repositoryPage);
        when(previewAssembler.assemblePage(repositoryPage, viewerId)).thenReturn(assembledPage);

        Page<PicksTemplatePreviewDTO> result = service.listTemplates(viewerId, PickOrigin.COMMUNITY, "A%", 1, 20, null);

        assertThat(result).isSameAs(assembledPage);
        verify(templateRepository).search(viewerId, PickOrigin.COMMUNITY, "A\\%", pageRequest);
        verify(previewAssembler).assemblePage(repositoryPage, viewerId);
        verifyNoMoreInteractions(templateRepository, previewAssembler);
    }

    @Test
    void shouldSearchForViewWithRelevanceAndAssembleReturnedPage() {
        UUID viewerId = UUID.randomUUID();
        PageRequest pageRequest = PageRequest.of(0, 10);
        Page<PicksTemplate> repositoryPage = Page.empty(pageRequest);
        Page<PicksTemplatePreviewDTO> assembledPage = Page.empty(pageRequest);
        when(pageRequestFactory.build(1, 10)).thenReturn(pageRequest);
        when(templateRepository.searchRelevance(viewerId, PickOrigin.COMMUNITY, "night", "night", pageRequest))
                .thenReturn(repositoryPage);
        when(previewAssembler.assemblePage(repositoryPage, viewerId)).thenReturn(assembledPage);

        Page<PicksTemplatePreviewDTO> result = service.searchForView(
                viewerId, PickOrigin.COMMUNITY, "night", 1, 10);

        assertThat(result).isSameAs(assembledPage);
        verify(templateRepository).searchRelevance(viewerId, PickOrigin.COMMUNITY, "night", "night", pageRequest);
        verify(previewAssembler).assemblePage(repositoryPage, viewerId);
    }

    @Test
    void shouldHideTemplateWhenCreatorAndViewerAreBlocked() {
        UUID viewerId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        PicksTemplate template = template(user(creatorId, UserRole.USER), PickOrigin.COMMUNITY);
        when(templateRepository.findById(template.getId())).thenReturn(Optional.of(template));
        org.mockito.Mockito.doThrow(new com.watchwise.watchwise_api.common.exception.NotFoundException("Resource not found"))
                .when(userBlockAccessService).assertContentVisible(viewerId, creatorId);

        assertThatThrownBy(() -> service.getTemplate(viewerId, template.getId()))
                .isInstanceOf(com.watchwise.watchwise_api.common.exception.NotFoundException.class);
        verifyNoInteractions(categoryRepository, previewAssembler);
    }

    @Test
    void shouldDispatchRecentSortToRecentSearch() {
        UUID viewerId = UUID.randomUUID();
        PageRequest pageRequest = PageRequest.of(0, 20);
        Page<PicksTemplate> repositoryPage = Page.empty(pageRequest);
        Page<PicksTemplatePreviewDTO> assembledPage = Page.empty(pageRequest);
        when(pageRequestFactory.build(1, 20)).thenReturn(pageRequest);
        when(templateRepository.searchRecent(viewerId, null, null, pageRequest)).thenReturn(repositoryPage);
        when(previewAssembler.assemblePage(repositoryPage, viewerId)).thenReturn(assembledPage);

        Page<PicksTemplatePreviewDTO> result = service.listTemplates(viewerId, null, null, 1, 20, PicksTemplateSort.RECENT);

        assertThat(result).isSameAs(assembledPage);
        verify(templateRepository).searchRecent(viewerId, null, null, pageRequest);
        verify(previewAssembler).assemblePage(repositoryPage, viewerId);
    }

    @Test
    void shouldDispatchMostPickedSortWithAuthenticatedViewerId() {
        UUID viewerId = UUID.randomUUID();
        PageRequest pageRequest = PageRequest.of(0, 20);
        Page<PicksTemplate> repositoryPage = Page.empty(pageRequest);
        Page<PicksTemplatePreviewDTO> assembledPage = Page.empty(pageRequest);
        when(pageRequestFactory.build(1, 20)).thenReturn(pageRequest);
        when(templateRepository.searchMostPicked(viewerId, PickOrigin.OFFICIAL, "Awards", pageRequest)).thenReturn(repositoryPage);
        when(previewAssembler.assemblePage(repositoryPage, viewerId)).thenReturn(assembledPage);

        Page<PicksTemplatePreviewDTO> result = service.listTemplates(viewerId, PickOrigin.OFFICIAL, "Awards", 1, 20, PicksTemplateSort.MOST_PICKED);

        assertThat(result).isSameAs(assembledPage);
        verify(templateRepository).searchMostPicked(viewerId, PickOrigin.OFFICIAL, "Awards", pageRequest);
        verify(previewAssembler).assemblePage(repositoryPage, viewerId);
    }

    @Test
    void shouldDispatchPopularWeekSortWithRollingSevenDaySince() {
        UUID viewerId = UUID.randomUUID();
        PageRequest pageRequest = PageRequest.of(0, 20);
        Page<PicksTemplate> repositoryPage = Page.empty(pageRequest);
        Page<PicksTemplatePreviewDTO> assembledPage = Page.empty(pageRequest);
        when(pageRequestFactory.build(1, 20)).thenReturn(pageRequest);
        when(templateRepository.searchPopularWeek(eq(viewerId), any(LocalDateTime.class), isNull(), eq("Awards"), eq(pageRequest)))
                .thenReturn(repositoryPage);
        when(previewAssembler.assemblePage(repositoryPage, viewerId)).thenReturn(assembledPage);
        LocalDateTime before = LocalDateTime.now().minusDays(7);

        Page<PicksTemplatePreviewDTO> result = service.listTemplates(viewerId, null, "Awards", 1, 20, PicksTemplateSort.POPULAR_WEEK);

        LocalDateTime after = LocalDateTime.now().minusDays(7);
        ArgumentCaptor<LocalDateTime> sinceCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        assertThat(result).isSameAs(assembledPage);
        verify(templateRepository).searchPopularWeek(eq(viewerId), sinceCaptor.capture(), isNull(), eq("Awards"), eq(pageRequest));
        assertThat(sinceCaptor.getValue()).isBetween(before, after);
        verify(previewAssembler).assemblePage(repositoryPage, viewerId);
    }

    @Test
    @DisplayName("[createTemplate] Should Delegate Creation Through Prepared Workflow - When Request Is Valid")
    void shouldDelegateCreationThroughPreparedWorkflow() {
        UUID actorId = UUID.randomUUID();
        PicksTemplateCreationDTO request = creation(List.of(categoryCreation(PickCategoryOptionMode.OPEN, List.of())));
        PreparedTemplateCreation prepared = new PreparedTemplateCreation(actorId, request, List.of(),
                "digest", LocalDateTime.now());
        LocalDateTime now = LocalDateTime.now();
        PicksTemplateResponseDTO expected = new PicksTemplateResponseDTO(UUID.randomUUID(), null,
                PickOrigin.COMMUNITY, "Awards", null, null, null, null, null, now, now, List.of());
        when(preparedTemplateCreationService.prepare(actorId, request)).thenReturn(prepared);
        when(preparedTemplateCreationService.createPrepared(actorId, prepared)).thenReturn(expected);

        assertThat(service.createTemplate(actorId, request)).isSameAs(expected);
        verify(preparedTemplateCreationService).prepare(actorId, request);
        verify(preparedTemplateCreationService).createPrepared(actorId, prepared);
        verifyNoInteractions(templateRepository, categoryRepository, userRepository);
    }

    @Test
    @DisplayName("[createTemplate] Should Reject Template Without Categories - When Creation Request Has No Categories")
    void shouldRejectTemplateWithoutCategoriesBeforePersistence() {
        UUID actorId = UUID.randomUUID();
        PicksTemplateCreationDTO request = creation(List.of());
        when(preparedTemplateCreationService.prepare(actorId, request))
                .thenThrow(new BadRequestException("At least one category is required"));

        assertThatThrownBy(() -> service.createTemplate(actorId, request)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(templateRepository, userRepository);
    }

    @Test
    @DisplayName("[createTemplate] Should Reject - When Creating A Template With An Empty Fixed Category")
    void shouldRejectFixedCategoryWithoutOptionsWhenCreatingTemplate() {
        UUID actorId = UUID.randomUUID();
        PicksTemplateCreationDTO request = creation(List.of(categoryCreation(PickCategoryOptionMode.FIXED, List.of())));
        when(preparedTemplateCreationService.prepare(actorId, request))
                .thenThrow(new BadRequestException("Fixed categories must have at least one option"));

        assertThatThrownBy(() -> service.createTemplate(actorId, request)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void shouldRejectFormerCreatorWhenTemplateIsDetached() {
        UUID actorId = UUID.randomUUID();
        PicksTemplate detached = template(null, PickOrigin.COMMUNITY);
        when(templateRepository.findByIdForUpdate(detached.getId())).thenReturn(Optional.of(detached));
        when(userRepository.findById(actorId)).thenReturn(Optional.of(user(actorId, UserRole.USER)));

        assertThatThrownBy(() -> service.detachOrDeleteTemplate(actorId, detached.getId())).isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("[detachOrDeleteTemplate] Should Tombstone Imported Links - Before Deleting An Unused Template")
    void shouldTombstoneImportedLinksBeforeDeletingUnusedTemplate() {
        UUID actorId = UUID.randomUUID();
        User actor = user(actorId, UserRole.USER);
        PicksTemplate template = template(actor, PickOrigin.COMMUNITY);
        when(templateRepository.findByIdForUpdate(template.getId())).thenReturn(Optional.of(template));
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(pickRepository.existsByPicksTemplateId(template.getId())).thenReturn(false);

        service.detachOrDeleteTemplate(actorId, template.getId());

        verify(importLedger).markDeletedByTarget("PICKS_TEMPLATE", template.getId());
        verify(templateRepository).delete(template);
    }

    @Test
    @DisplayName("[detachOrDeleteTemplate] Should Tombstone Imported Links - When An Admin Detaches A Used Template")
    void shouldKeepOriginWhenAdminDetachesUsedTemplate() {
        UUID actorId = UUID.randomUUID();
        PicksTemplate template = template(user(UUID.randomUUID(), UserRole.USER), PickOrigin.COMMUNITY);
        when(templateRepository.findByIdForUpdate(template.getId())).thenReturn(Optional.of(template));
        when(userRepository.findById(actorId)).thenReturn(Optional.of(user(actorId, UserRole.ADMIN)));
        when(pickRepository.existsByPicksTemplateId(template.getId())).thenReturn(true);

        service.detachOrDeleteTemplate(actorId, template.getId());

        verify(importLedger).markDeletedByTarget("PICKS_TEMPLATE", template.getId());
        ArgumentCaptor<PicksTemplate> captor = ArgumentCaptor.forClass(PicksTemplate.class);
        verify(templateRepository).save(captor.capture());
        assertThat(captor.getValue().getCreator()).isNull();
        assertThat(captor.getValue().getOrigin()).isEqualTo(PickOrigin.COMMUNITY);
    }

    @Test
    @DisplayName("[updateTemplate] Should Clear Eligibility Period - When Clear Is Explicitly Requested")
    void shouldClearUnusedEligibilityPeriodOnlyWhenExplicitlyRequested() {
        UUID actorId = UUID.randomUUID();
        User actor = user(actorId, UserRole.USER);
        PicksTemplate template = PicksTemplate.builder().id(UUID.randomUUID()).creator(actor).origin(PickOrigin.COMMUNITY)
                .name("Awards").eligibilityStartDate(LocalDate.of(2026, 1, 1)).eligibilityEndDate(LocalDate.of(2026, 12, 31))
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(templateRepository.findByIdForUpdate(template.getId())).thenReturn(Optional.of(template));
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(categoryRepository.findByPicksTemplateIdOrderByGroupAscDisplayOrderAsc(template.getId())).thenReturn(List.of());
        when(responseAssembler.toResponse(template, actorId)).thenAnswer(call -> new PicksTemplateResponseDTO(
                template.getId(), null, template.getOrigin(), template.getName(), template.getDescription(),
                template.getCoverImage(), template.getInstructions(), template.getEligibilityStartDate(),
                template.getEligibilityEndDate(), template.getCreatedAt(), template.getUpdatedAt(), List.of()));

        PicksTemplateResponseDTO result = service.updateTemplate(actorId, template.getId(),
                new PicksTemplatePatchDTO(null, null, null, null, null, null, true));

        assertThat(result.eligibilityStartDate()).isNull();
        assertThat(result.eligibilityEndDate()).isNull();
    }

    @Test
    void shouldBlockExplicitEligibilityClearAfterContentCategoryUse() {
        UUID actorId = UUID.randomUUID();
        User actor = user(actorId, UserRole.USER);
        PicksTemplate template = template(actor, PickOrigin.COMMUNITY);
        template.setEligibilityStartDate(LocalDate.of(2026, 1, 1));
        template.setEligibilityEndDate(LocalDate.of(2026, 12, 31));
        PicksTemplateCategory category = category(PickCategoryOptionMode.OPEN);
        category.setId(UUID.randomUUID());
        category.setAllowedType(PickAllowedType.MOVIE);
        when(templateRepository.findByIdForUpdate(template.getId())).thenReturn(Optional.of(template));
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(categoryRepository.findByPicksTemplateIdOrderByGroupAscDisplayOrderAsc(template.getId())).thenReturn(List.of(category));
        when(selectionRepository.existsByCategoryId(category.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.updateTemplate(actorId, template.getId(),
                new PicksTemplatePatchDTO(null, null, null, null, null, null, true))).isInstanceOf(ConflictException.class);
    }

    private PicksTemplateCreationDTO creation(List<PicksTemplateCategoryCreationDTO> categories) {
        return new PicksTemplateCreationDTO("Awards", null, null, null, null, null, categories);
    }
    private PicksTemplateCategoryCreationDTO categoryCreation(PickCategoryOptionMode mode, List<PicksTemplateOptionCreationDTO> options) {
        return new PicksTemplateCategoryCreationDTO("Best", null, PickCategoryGroup.PRIMARY, 1, PickAllowedType.MOVIE, mode, options);
    }
    private PicksTemplateCategory category(PickCategoryOptionMode mode) {
        return PicksTemplateCategory.builder().id(UUID.randomUUID()).picksTemplate(template(null, PickOrigin.COMMUNITY))
                .name("Best").group(PickCategoryGroup.PRIMARY).displayOrder(1).allowedType(PickAllowedType.MOVIE).optionMode(mode)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }
    private PicksTemplate template(User creator, PickOrigin origin) {
        return PicksTemplate.builder().id(UUID.randomUUID()).creator(creator).origin(origin).name("Awards")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }
    private User user(UUID id, UserRole role) {
        return User.builder().id(id).role(role).username("user").email("user@example.com").password("hash")
                .profilePicture("image").isProfilePublic(true).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }
}
