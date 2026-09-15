package com.watchwise.watchwise_api.pickstemplate.mapper;

import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateCategoryCreationDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplatePreviewDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateCreationDTO;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateCategory;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface PicksTemplateMapper {
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "creator", ignore = true)
    @Mapping(target = "origin", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(source = "name", target = "name")
    @Mapping(source = "description", target = "description")
    @Mapping(source = "coverImage", target = "coverImage")
    @Mapping(source = "instructions", target = "instructions")
    @Mapping(source = "eligibilityStartDate", target = "eligibilityStartDate")
    @Mapping(source = "eligibilityEndDate", target = "eligibilityEndDate")
    PicksTemplate picksTemplateCreationDtoToPicksTemplate(PicksTemplateCreationDTO dto);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "picksTemplate", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(source = "name", target = "name")
    @Mapping(source = "description", target = "description")
    @Mapping(source = "group", target = "group")
    @Mapping(source = "displayOrder", target = "displayOrder")
    @Mapping(source = "allowedType", target = "allowedType")
    @Mapping(source = "optionMode", target = "optionMode")
    PicksTemplateCategory categoryCreationDtoToPicksTemplateCategory(PicksTemplateCategoryCreationDTO dto);

    @Mapping(source = "id", target = "id")
    @Mapping(source = "origin", target = "origin")
    @Mapping(source = "name", target = "name")
    @Mapping(source = "description", target = "description")
    @Mapping(source = "coverImage", target = "coverImage")
    PicksTemplatePreviewDTO picksTemplateToPreviewDto(PicksTemplate picksTemplate);
}
