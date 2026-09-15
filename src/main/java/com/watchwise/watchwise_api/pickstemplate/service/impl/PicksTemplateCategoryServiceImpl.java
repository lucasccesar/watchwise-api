package com.watchwise.watchwise_api.pickstemplate.service.impl;

import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.pick.repository.PickSelectionRepository;
import com.watchwise.watchwise_api.pick.service.PickTargetService;
import com.watchwise.watchwise_api.pick.service.ResolvedPickTarget;
import com.watchwise.watchwise_api.pickstemplate.dto.*;
import com.watchwise.watchwise_api.pickstemplate.entity.*;
import com.watchwise.watchwise_api.pickstemplate.mapper.PicksTemplateOptionMapper;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateCategoryRepository;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateOptionRepository;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateRepository;
import com.watchwise.watchwise_api.pickstemplate.service.PicksTemplateCategoryService;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.entity.UserRole;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PicksTemplateCategoryServiceImpl implements PicksTemplateCategoryService {

    private final PicksTemplateRepository templateRepository;
    private final PicksTemplateCategoryRepository categoryRepository;
    private final PicksTemplateOptionRepository optionRepository;
    private final PickSelectionRepository selectionRepository;
    private final UserRepository userRepository;
    private final PickTargetService pickTargetService;
    private final PicksTemplateOptionMapper optionMapper;

    @Override
    @Transactional
    public PicksTemplateCategoryDTO addCategory(UUID actorId, UUID templateId, PicksTemplateCategoryCreationDTO dto) {
        PicksTemplate template = lockTemplate(templateId);
        assertCanManage(actorId, template);
        LocalDateTime now = LocalDateTime.now();
        if (dto.optionMode() == PickCategoryOptionMode.OPEN && dto.options() != null && !dto.options().isEmpty()) {
            throw new com.watchwise.watchwise_api.common.exception.BadRequestException("Open categories cannot receive fixed options");
        }
        PicksTemplateCategory category = categoryRepository.save(PicksTemplateCategory.builder().picksTemplate(template)
                .name(dto.name()).description(dto.description()).group(dto.group()).displayOrder(dto.displayOrder())
                .allowedType(dto.allowedType()).optionMode(dto.optionMode()).createdAt(now).updatedAt(now).build());
        saveInitialOptions(actorId, template, category, dto.options(), now);
        return toDto(category);
    }

    @Override
    @Transactional
    public PicksTemplateCategoryDTO updateCategory(UUID actorId, UUID templateId, UUID categoryId, PicksTemplateCategoryPatchDTO dto) {
        PicksTemplate template = lockTemplate(templateId);
        assertCanManage(actorId, template);
        PicksTemplateCategory category = lockCategory(templateId, categoryId);
        if (selectionRepository.existsByCategoryId(categoryId)) {
            throw new ConflictException("A category with selections cannot be changed");
        }
        if (dto.name() != null) category.setName(dto.name());
        if (dto.description() != null) category.setDescription(dto.description());
        if (dto.group() != null) category.setGroup(dto.group());
        if (dto.displayOrder() != null) category.setDisplayOrder(dto.displayOrder());
        if (dto.allowedType() != null && dto.allowedType() != category.getAllowedType()
                && !optionRepository.findByCategoryId(categoryId).isEmpty()) {
            throw new ConflictException("A category with fixed options cannot change allowed type");
        }
        if (dto.allowedType() != null) category.setAllowedType(dto.allowedType());
        if (dto.optionMode() != null) {
            if (dto.optionMode() == PickCategoryOptionMode.OPEN && !optionRepository.findByCategoryId(categoryId).isEmpty()) {
                throw new ConflictException("A category with fixed options cannot become open");
            }
            category.setOptionMode(dto.optionMode());
        }
        category.setUpdatedAt(LocalDateTime.now());
        return toDto(category);
    }

    @Override
    @Transactional
    public void deleteCategory(UUID actorId, UUID templateId, UUID categoryId) {
        PicksTemplate template = lockTemplate(templateId);
        assertCanManage(actorId, template);
        PicksTemplateCategory category = lockCategory(templateId, categoryId);
        if (selectionRepository.existsByCategoryId(categoryId)) {
            throw new ConflictException("A category with selections cannot be deleted");
        }
        categoryRepository.delete(category);
    }

    private void saveInitialOptions(UUID actorId, PicksTemplate template, PicksTemplateCategory category,
                                    List<PicksTemplateOptionCreationDTO> options, LocalDateTime now) {
        if (options == null) return;
        for (PicksTemplateOptionCreationDTO optionDto : options) {
            ResolvedPickTarget target = pickTargetService.validateFixedOption(actorId, template, category, optionDto.target());
            optionRepository.save(PicksTemplateOption.builder().category(category).content(target.content())
                    .personTmdbId(target.personTmdbId()).contextContent(target.contextContent()).createdAt(now).build());
        }
    }

    private PicksTemplate lockTemplate(UUID templateId) {
        return templateRepository.findByIdForUpdate(templateId)
                .orElseThrow(() -> new NotFoundException("Picks template not found"));
    }

    private PicksTemplateCategory lockCategory(UUID templateId, UUID categoryId) {
        PicksTemplateCategory category = categoryRepository.findByIdForUpdate(categoryId)
                .orElseThrow(() -> new NotFoundException("Picks template category not found"));
        if (!category.getPicksTemplate().getId().equals(templateId)) {
            throw new NotFoundException("Picks template category not found");
        }
        return category;
    }

    private void assertCanManage(UUID actorId, PicksTemplate template) {
        User actor = userRepository.findById(actorId).orElseThrow(() -> new NotFoundException("User not found"));
        if (actor.getRole() == UserRole.ADMIN || (template.getCreator() != null && actorId.equals(template.getCreator().getId()))) return;
        throw new ForbiddenException("You cannot manage this picks template");
    }

    private PicksTemplateCategoryDTO toDto(PicksTemplateCategory category) {
        List<PicksTemplateOptionDTO> options = optionRepository.findByCategoryId(category.getId()).stream()
                .map(option -> new PicksTemplateOptionDTO(option.getId(), optionMapper.picksTemplateOptionToSearchDto(option), option.getCreatedAt())).toList();
        return new PicksTemplateCategoryDTO(category.getId(), category.getName(), category.getDescription(), category.getGroup(),
                category.getDisplayOrder(), category.getAllowedType(), category.getOptionMode(), category.getCreatedAt(), category.getUpdatedAt(), options);
    }
}
