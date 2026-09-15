package com.watchwise.watchwise_api.pickstemplate.service.impl;

import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.pick.repository.PickSelectionRepository;
import com.watchwise.watchwise_api.pick.service.PickTargetService;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateCategoryPatchDTO;
import com.watchwise.watchwise_api.pickstemplate.entity.*;
import com.watchwise.watchwise_api.pickstemplate.mapper.PicksTemplateOptionMapper;
import com.watchwise.watchwise_api.pickstemplate.repository.*;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.entity.UserRole;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PicksTemplateCategoryServiceImplTest {
    @Mock PicksTemplateRepository templateRepository;
    @Mock PicksTemplateCategoryRepository categoryRepository;
    @Mock PicksTemplateOptionRepository optionRepository;
    @Mock PickSelectionRepository selectionRepository;
    @Mock UserRepository userRepository;
    @Mock PickTargetService pickTargetService;
    @Mock PicksTemplateOptionMapper optionMapper;
    @InjectMocks PicksTemplateCategoryServiceImpl service;

    @Test
    void shouldFreezeCategoryThatAlreadyHasSelections() {
        UUID actorId = UUID.randomUUID();
        PicksTemplate template = template(actorId);
        PicksTemplateCategory category = category(template);
        when(templateRepository.findByIdForUpdate(template.getId())).thenReturn(Optional.of(template));
        when(userRepository.findById(actorId)).thenReturn(Optional.of(user(actorId)));
        when(categoryRepository.findByIdForUpdate(category.getId())).thenReturn(Optional.of(category));
        when(selectionRepository.existsByCategoryId(category.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.updateCategory(actorId, template.getId(), category.getId(),
                new PicksTemplateCategoryPatchDTO("Changed", null, null, null, null, null))).isInstanceOf(ConflictException.class);
    }

    private PicksTemplate template(UUID actorId) {
        return PicksTemplate.builder().id(UUID.randomUUID()).creator(user(actorId)).origin(PickOrigin.COMMUNITY).name("Awards")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }
    private PicksTemplateCategory category(PicksTemplate template) {
        return PicksTemplateCategory.builder().id(UUID.randomUUID()).picksTemplate(template).name("Best")
                .group(PickCategoryGroup.PRIMARY).displayOrder(1).allowedType(PickAllowedType.MOVIE).optionMode(PickCategoryOptionMode.FIXED)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }
    private User user(UUID id) {
        return User.builder().id(id).role(UserRole.USER).username("owner").email("owner@example.com").password("hash")
                .profilePicture("image").isProfilePublic(true).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }
}
