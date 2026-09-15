package com.watchwise.watchwise_api.pickstemplate.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.pick.repository.PickRepository;
import com.watchwise.watchwise_api.pick.repository.PickSelectionRepository;
import com.watchwise.watchwise_api.pick.service.PickTargetService;
import com.watchwise.watchwise_api.pick.service.ResolvedPickTarget;
import com.watchwise.watchwise_api.pickstemplate.dto.*;
import com.watchwise.watchwise_api.pickstemplate.entity.*;
import com.watchwise.watchwise_api.pickstemplate.mapper.PicksTemplateMapper;
import com.watchwise.watchwise_api.pickstemplate.mapper.PicksTemplateOptionMapper;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateCategoryRepository;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateOptionRepository;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateRepository;
import com.watchwise.watchwise_api.pickstemplate.service.PicksTemplateService;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.entity.UserRole;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PicksTemplateServiceImpl implements PicksTemplateService {

    private final PicksTemplateRepository templateRepository;
    private final PicksTemplateCategoryRepository categoryRepository;
    private final PicksTemplateOptionRepository optionRepository;
    private final PickRepository pickRepository;
    private final PickSelectionRepository selectionRepository;
    private final UserRepository userRepository;
    private final PickTargetService pickTargetService;
    private final PicksTemplateMapper templateMapper;
    private final PicksTemplateOptionMapper optionMapper;
    private final UserMapper userMapper;
    private final PageRequestFactory pageRequestFactory;

    @Override
    @Transactional
    public PicksTemplateResponseDTO createTemplate(UUID actorId, PicksTemplateCreationDTO dto) {
        validateCreation(dto);
        User actor = requireActor(actorId);
        LocalDateTime now = LocalDateTime.now();
        PicksTemplate template = PicksTemplate.builder().creator(actor)
                .origin(actor.getRole() == UserRole.ADMIN ? PickOrigin.OFFICIAL : PickOrigin.COMMUNITY)
                .name(dto.name()).description(dto.description()).coverImage(dto.coverImage()).instructions(dto.instructions())
                .eligibilityStartDate(dto.eligibilityStartDate()).eligibilityEndDate(dto.eligibilityEndDate())
                .createdAt(now).updatedAt(now).build();
        PicksTemplate saved = templateRepository.save(template);

        for (PicksTemplateCategoryCreationDTO categoryDto : dto.categories()) {
            PicksTemplateCategory category = buildCategory(saved, categoryDto, now);
            PicksTemplateCategory savedCategory = categoryRepository.save(category);
            saveInitialOptions(actorId, saved, savedCategory, categoryDto.options(), now);
        }
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PicksTemplatePreviewDTO> listTemplates(UUID viewerId, PickOrigin origin, String name, Integer page, Integer size) {
        PageRequest pageRequest = pageRequestFactory.build(page, size);
        return templateRepository.search(origin, escapeLike(name), pageRequest).map(templateMapper::picksTemplateToPreviewDto);
    }

    @Override
    @Transactional(readOnly = true)
    public PicksTemplateResponseDTO getTemplate(UUID viewerId, UUID templateId) {
        return toResponse(findTemplate(templateId));
    }

    @Override
    @Transactional
    public PicksTemplateResponseDTO updateTemplate(UUID actorId, UUID templateId, PicksTemplatePatchDTO dto) {
        validateDatePair(dto.eligibilityStartDate(), dto.eligibilityEndDate());
        PicksTemplate template = templateRepository.findByIdForUpdate(templateId)
                .orElseThrow(() -> new NotFoundException("Picks template not found"));
        assertCanManage(actorId, template);
        if (changesPeriod(template, dto) && hasUsedContentCategory(templateId)) {
            throw new ConflictException("Eligibility dates cannot change after a content category is used");
        }
        applyPatch(template, dto);
        template.setUpdatedAt(LocalDateTime.now());
        return toResponse(template);
    }

    @Override
    @Transactional
    public void detachOrDeleteTemplate(UUID actorId, UUID templateId) {
        PicksTemplate template = templateRepository.findByIdForUpdate(templateId)
                .orElseThrow(() -> new NotFoundException("Picks template not found"));
        assertCanManage(actorId, template);
        if (!pickRepository.existsByPicksTemplateId(templateId)) {
            templateRepository.delete(template);
            return;
        }
        templateRepository.save(detached(template));
    }

    private void validateCreation(PicksTemplateCreationDTO dto) {
        if (dto == null || dto.categories() == null || dto.categories().isEmpty()) {
            throw new BadRequestException("At least one category is required");
        }
        validateDatePair(dto.eligibilityStartDate(), dto.eligibilityEndDate());
        dto.categories().forEach(category -> {
            if (category == null || category.optionMode() == null || category.allowedType() == null || category.group() == null) {
                throw new BadRequestException("Category configuration is incomplete");
            }
            if (category.optionMode() == PickCategoryOptionMode.OPEN
                    && category.options() != null && !category.options().isEmpty()) {
                throw new BadRequestException("Open categories cannot receive fixed options");
            }
        });
    }

    private PicksTemplateCategory buildCategory(PicksTemplate template, PicksTemplateCategoryCreationDTO dto, LocalDateTime now) {
        return PicksTemplateCategory.builder().picksTemplate(template).name(dto.name()).description(dto.description())
                .group(dto.group()).displayOrder(dto.displayOrder()).allowedType(dto.allowedType()).optionMode(dto.optionMode())
                .createdAt(now).updatedAt(now).build();
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

    private void applyPatch(PicksTemplate template, PicksTemplatePatchDTO dto) {
        if (dto.name() != null) template.setName(dto.name());
        if (dto.description() != null) template.setDescription(dto.description());
        if (dto.coverImage() != null) template.setCoverImage(dto.coverImage());
        if (dto.instructions() != null) template.setInstructions(dto.instructions());
        if (dto.eligibilityStartDate() != null || dto.eligibilityEndDate() != null) {
            template.setEligibilityStartDate(dto.eligibilityStartDate());
            template.setEligibilityEndDate(dto.eligibilityEndDate());
        }
    }

    private boolean changesPeriod(PicksTemplate template, PicksTemplatePatchDTO dto) {
        return (dto.eligibilityStartDate() != null || dto.eligibilityEndDate() != null)
                && (!java.util.Objects.equals(template.getEligibilityStartDate(), dto.eligibilityStartDate())
                || !java.util.Objects.equals(template.getEligibilityEndDate(), dto.eligibilityEndDate()));
    }

    private boolean hasUsedContentCategory(UUID templateId) {
        return categoryRepository.findByPicksTemplateIdOrderByGroupAscDisplayOrderAsc(templateId).stream()
                .filter(category -> category.getAllowedType() != PickAllowedType.PERSON)
                .anyMatch(category -> selectionRepository.existsByCategoryId(category.getId()));
    }

    private void validateDatePair(LocalDate start, LocalDate end) {
        if ((start == null) != (end == null) || (start != null && start.isAfter(end))) {
            throw new BadRequestException("Eligibility dates must be both absent or ordered");
        }
    }

    private User requireActor(UUID actorId) {
        return userRepository.findById(actorId).orElseThrow(() -> new NotFoundException("User not found"));
    }

    private void assertCanManage(UUID actorId, PicksTemplate template) {
        User actor = requireActor(actorId);
        if (actor.getRole() == UserRole.ADMIN) return;
        if (template.getCreator() != null && actorId.equals(template.getCreator().getId())) return;
        throw new ForbiddenException("You cannot manage this picks template");
    }

    private PicksTemplate findTemplate(UUID id) {
        return templateRepository.findById(id).orElseThrow(() -> new NotFoundException("Picks template not found"));
    }

    private PicksTemplate detached(PicksTemplate template) {
        return PicksTemplate.builder().id(template.getId()).creator(null).origin(template.getOrigin()).name(template.getName())
                .description(template.getDescription()).coverImage(template.getCoverImage()).instructions(template.getInstructions())
                .eligibilityStartDate(template.getEligibilityStartDate()).eligibilityEndDate(template.getEligibilityEndDate())
                .createdAt(template.getCreatedAt()).updatedAt(LocalDateTime.now()).build();
    }

    private PicksTemplateResponseDTO toResponse(PicksTemplate template) {
        List<PicksTemplateCategoryDTO> categories = categoryRepository
                .findByPicksTemplateIdOrderByGroupAscDisplayOrderAsc(template.getId()).stream().map(this::toCategoryDto).toList();
        return new PicksTemplateResponseDTO(template.getId(), template.getCreator() == null ? null : userMapper.userToUserPreviewDto(template.getCreator()),
                template.getOrigin(), template.getName(), template.getDescription(), template.getCoverImage(), template.getInstructions(),
                template.getEligibilityStartDate(), template.getEligibilityEndDate(), template.getCreatedAt(), template.getUpdatedAt(), categories);
    }

    private PicksTemplateCategoryDTO toCategoryDto(PicksTemplateCategory category) {
        List<PicksTemplateOptionDTO> options = optionRepository.findByCategoryId(category.getId()).stream()
                .map(option -> new PicksTemplateOptionDTO(option.getId(), optionMapper.picksTemplateOptionToSearchDto(option), option.getCreatedAt())).toList();
        return new PicksTemplateCategoryDTO(category.getId(), category.getName(), category.getDescription(), category.getGroup(),
                category.getDisplayOrder(), category.getAllowedType(), category.getOptionMode(), category.getCreatedAt(), category.getUpdatedAt(), options);
    }

    private String escapeLike(String value) {
        if (value == null || value.isBlank()) return null;
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
