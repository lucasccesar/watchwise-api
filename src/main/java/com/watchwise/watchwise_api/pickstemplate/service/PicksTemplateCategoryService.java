package com.watchwise.watchwise_api.pickstemplate.service;

import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateCategoryCreationDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateCategoryDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateCategoryPatchDTO;

import java.util.UUID;

public interface PicksTemplateCategoryService {
    PicksTemplateCategoryDTO addCategory(UUID actorId, UUID templateId, PicksTemplateCategoryCreationDTO dto);
    PicksTemplateCategoryDTO updateCategory(UUID actorId, UUID templateId, UUID categoryId, PicksTemplateCategoryPatchDTO dto);
    void deleteCategory(UUID actorId, UUID templateId, UUID categoryId);
}
