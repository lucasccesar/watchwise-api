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
import com.watchwise.watchwise_api.pickstemplate.mapper.PicksTemplateMapper;
import com.watchwise.watchwise_api.pickstemplate.mapper.PicksTemplateOptionMapper;
import com.watchwise.watchwise_api.pickstemplate.repository.*;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.entity.UserRole;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    @Mock PickTargetService pickTargetService;
    @Mock PicksTemplateMapper templateMapper;
    @Mock PicksTemplateOptionMapper optionMapper;
    @Mock UserMapper userMapper;
    @Mock PageRequestFactory pageRequestFactory;
    @InjectMocks PicksTemplateServiceImpl service;

    @Test
    void shouldDeriveOfficialOriginFromPersistedAdminRole() {
        UUID actorId = UUID.randomUUID();
        User admin = user(actorId, UserRole.ADMIN);
        PicksTemplateCategory category = category(PickCategoryOptionMode.FIXED);
        when(userRepository.findById(actorId)).thenReturn(Optional.of(admin));
        when(templateRepository.save(any())).thenAnswer(invocation -> {
            PicksTemplate template = invocation.getArgument(0); template.setId(UUID.randomUUID()); return template;
        });
        when(categoryRepository.save(any())).thenAnswer(invocation -> {
            PicksTemplateCategory saved = invocation.getArgument(0); saved.setId(UUID.randomUUID()); return saved;
        });
        when(categoryRepository.findByPicksTemplateIdOrderByGroupAscDisplayOrderAsc(any())).thenReturn(List.of(category));
        when(optionRepository.findByCategoryId(any())).thenReturn(List.of());
        when(userMapper.userToUserPreviewDto(admin)).thenReturn(new UserPreviewDTO(actorId, "admin", "image", true));

        PicksTemplateResponseDTO result = service.createTemplate(actorId, creation(List.of(categoryCreation(PickCategoryOptionMode.FIXED, List.of()))));

        assertThat(result.origin()).isEqualTo(PickOrigin.OFFICIAL);
    }

    @Test
    void shouldRejectTemplateWithoutCategoriesBeforePersistence() {
        UUID actorId = UUID.randomUUID();
        assertThatThrownBy(() -> service.createTemplate(actorId, creation(List.of()))).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(templateRepository, userRepository);
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
    void shouldKeepOriginWhenAdminDetachesUsedTemplate() {
        UUID actorId = UUID.randomUUID();
        PicksTemplate template = template(user(UUID.randomUUID(), UserRole.USER), PickOrigin.COMMUNITY);
        when(templateRepository.findByIdForUpdate(template.getId())).thenReturn(Optional.of(template));
        when(userRepository.findById(actorId)).thenReturn(Optional.of(user(actorId, UserRole.ADMIN)));
        when(pickRepository.existsByPicksTemplateId(template.getId())).thenReturn(true);

        service.detachOrDeleteTemplate(actorId, template.getId());

        ArgumentCaptor<PicksTemplate> captor = ArgumentCaptor.forClass(PicksTemplate.class);
        verify(templateRepository).save(captor.capture());
        assertThat(captor.getValue().getCreator()).isNull();
        assertThat(captor.getValue().getOrigin()).isEqualTo(PickOrigin.COMMUNITY);
    }

    @Test
    void shouldClearUnusedEligibilityPeriodOnlyWhenExplicitlyRequested() {
        UUID actorId = UUID.randomUUID();
        User actor = user(actorId, UserRole.USER);
        PicksTemplate template = PicksTemplate.builder().id(UUID.randomUUID()).creator(actor).origin(PickOrigin.COMMUNITY)
                .name("Awards").eligibilityStartDate(LocalDate.of(2026, 1, 1)).eligibilityEndDate(LocalDate.of(2026, 12, 31))
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(templateRepository.findByIdForUpdate(template.getId())).thenReturn(Optional.of(template));
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(categoryRepository.findByPicksTemplateIdOrderByGroupAscDisplayOrderAsc(template.getId())).thenReturn(List.of());

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
