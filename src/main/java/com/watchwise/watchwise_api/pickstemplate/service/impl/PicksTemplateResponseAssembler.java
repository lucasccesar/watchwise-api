package com.watchwise.watchwise_api.pickstemplate.service.impl;

import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateCategoryDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateOptionDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplatePreviewDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateResponseDTO;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateCategory;
import com.watchwise.watchwise_api.pickstemplate.mapper.PicksTemplateOptionMapper;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateCategoryRepository;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateOptionRepository;
import com.watchwise.watchwise_api.pickstemplate.service.PicksTemplateOptionService;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
public class PicksTemplateResponseAssembler {
    private final PicksTemplateCategoryRepository categories;
    private final PicksTemplateOptionRepository options;
    private final PicksTemplateOptionMapper optionMapper;
    private final UserMapper userMapper;
    private final PicksTemplatePreviewAssembler previewAssembler;

    public PicksTemplateResponseAssembler(PicksTemplateCategoryRepository categories,
                                          PicksTemplateOptionRepository options,
                                          PicksTemplateOptionMapper optionMapper,
                                          UserMapper userMapper,
                                          PicksTemplatePreviewAssembler previewAssembler) {
        this.categories = categories;
        this.options = options;
        this.optionMapper = optionMapper;
        this.userMapper = userMapper;
        this.previewAssembler = previewAssembler;
    }

    public PicksTemplateResponseDTO toResponse(PicksTemplate template, UUID viewerId) {
        List<PicksTemplateCategoryDTO> categoryDtos = categories
                .findByPicksTemplateIdOrderByGroupAscDisplayOrderAsc(template.getId())
                .stream().map(this::toCategoryDto).toList();
        PicksTemplatePreviewDTO preview = Objects.requireNonNull(previewAssembler.assembleOne(template, viewerId),
                "Persisted template must have a preview");
        return new PicksTemplateResponseDTO(template.getId(),
                template.getCreator() == null ? null : userMapper.userToUserPreviewDto(template.getCreator()),
                template.getOrigin(), template.getName(), template.getDescription(), template.getCoverImage(),
                template.getInstructions(), template.getEligibilityStartDate(), template.getEligibilityEndDate(),
                template.getCreatedAt(), template.getUpdatedAt(), categoryDtos, preview.picksCount(),
                preview.categoriesCount(), preview.categoryNames(), preview.likesCount(), preview.commentsCount(),
                preview.isLikedByViewer(), preview.myPicksCount(), preview.latestMyPickId());
    }

    private PicksTemplateCategoryDTO toCategoryDto(PicksTemplateCategory category) {
        List<PicksTemplateOptionDTO> optionDtos = options.findByCategoryId(category.getId()).stream()
                .map(option -> new PicksTemplateOptionDTO(option.getId(),
                        optionMapper.picksTemplateOptionToSearchDto(option), option.getCreatedAt())).toList();
        return new PicksTemplateCategoryDTO(category.getId(), category.getName(), category.getDescription(),
                category.getGroup(), category.getDisplayOrder(), category.getAllowedType(), category.getOptionMode(),
                category.getCreatedAt(), category.getUpdatedAt(), optionDtos);
    }
}
